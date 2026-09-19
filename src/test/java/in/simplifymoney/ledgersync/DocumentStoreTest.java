package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.IndexedDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentStoreTest {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private IndexedDocumentStore docStore;

    @BeforeEach
    void setUp() {
        docStore = new IndexedDocumentStore();
    }

    @Test
    void servesAccountMonthNewestFirstWithExactIndexSeeks() {
        NormalizedTxn t1 = new NormalizedTxn("4821", OffsetDateTime.of(2026, 7, 5, 10, 0, 0, 0, IST),
                Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "AMAZON", List.of("m-1"));
        NormalizedTxn t2 = new NormalizedTxn("4821", OffsetDateTime.of(2026, 7, 10, 15, 30, 0, 0, IST),
                Direction.DEBIT, new BigDecimal("250.00"), Category.SPEND, "SWIGGY", List.of("m-2"));
        NormalizedTxn t3 = new NormalizedTxn("4821", OffsetDateTime.of(2026, 8, 1, 9, 0, 0, 0, IST),
                Direction.CREDIT, new BigDecimal("45000.00"), Category.INCOME, "SALARY", List.of("m-3"));

        docStore.save(t1);
        docStore.save(t2);
        docStore.save(t3);

        List<NormalizedTxn> july = docStore.forAccountMonth("4821", YearMonth.of(2026, 7));
        assertEquals(2, july.size());
        assertEquals(t2, july.get(0), "newest first");
        assertEquals(t1, july.get(1));
        assertEquals(2, docStore.lastQ1Examined());
        assertEquals(2, docStore.lastQ1Returned());
    }

    @Test
    void servesCategoryTotalsDirectly() {
        docStore.save(new NormalizedTxn("4821", OffsetDateTime.of(2026, 7, 1, 10, 0, 0, 0, IST),
                Direction.DEBIT, new BigDecimal("500.00"), Category.SPEND, "STORE", List.of("m-1")));
        docStore.save(new NormalizedTxn("4821", OffsetDateTime.of(2026, 7, 2, 10, 0, 0, 0, IST),
                Direction.DEBIT, new BigDecimal("50.00"), Category.MICRO, "UPI/TEA", List.of("m-2")));
        docStore.save(new NormalizedTxn("4821", OffsetDateTime.of(2026, 7, 3, 10, 0, 0, 0, IST),
                Direction.CREDIT, new BigDecimal("1000.00"), Category.INCOME, "REFUND", List.of("m-3")));

        Map<Category, BigDecimal> totals = docStore.categoryTotals("4821");
        assertEquals(new BigDecimal("500.00"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("50.00"), totals.get(Category.MICRO));
        assertEquals(new BigDecimal("1000.00"), totals.get(Category.INCOME));
        assertEquals(1, docStore.lastQ2Examined());
        assertEquals(1, docStore.lastQ2Returned());
    }

    @Test
    void servesByMessageIdViaMultikeyIndex() {
        NormalizedTxn txn = new NormalizedTxn("9075", OffsetDateTime.of(2026, 7, 4, 12, 0, 0, 0, IST),
                Direction.DEBIT, new BigDecimal("1299.50"), Category.SPEND, "MYNTRA", List.of("m-sms-1", "m-email-1"));
        docStore.save(txn);

        Optional<NormalizedTxn> bySms = docStore.byMessageId("m-sms-1");
        assertTrue(bySms.isPresent());
        assertEquals(txn, bySms.get());
        assertEquals(1, docStore.lastQ3Examined());
        assertEquals(1, docStore.lastQ3Returned());

        Optional<NormalizedTxn> byEmail = docStore.byMessageId("m-email-1");
        assertTrue(byEmail.isPresent());
        assertEquals(txn, byEmail.get());

        Optional<NormalizedTxn> missing = docStore.byMessageId("m-unknown");
        assertFalse(missing.isPresent());
    }

    @Test
    void backfillDeduplicatesDirtySqlRowsAndIsIdempotent(@TempDir Path tempDir) {
        Path db = tempDir.resolve("test-ledger");
        Path migrations = Path.of("db", "migration");

        try (SqlLedgerStore sql = new SqlLedgerStore(db)) {
            sql.migrate(migrations);
            long sqlCount = sql.count();
            assertTrue(sqlCount >= 15, "contains dirty seed rows");

            Backfill backfill = new Backfill(sql, docStore);
            Backfill.Result res1 = backfill.run();

            assertTrue(res1.read() >= 15);
            assertTrue(res1.skipped() > 0, "duplicate rows merged/skipped");
            assertTrue(res1.written() < res1.read());

            // Re-run backfill to verify idempotency
            Backfill.Result res2 = backfill.run();
            assertEquals(res1.written(), res2.written());
            assertEquals(res1.written(), docStore.allDocuments().size());
        }
    }

    @Test
    void consistencyCheckerCatchesTamperedDocuments(@TempDir Path tempDir) {
        Path db = tempDir.resolve("test-ledger");
        Path migrations = Path.of("db", "migration");

        try (SqlLedgerStore sql = new SqlLedgerStore(db)) {
            sql.migrate(migrations);

            // Clean backfill
            Backfill backfill = new Backfill(sql, docStore);
            backfill.run();

            ConsistencyChecker checker = new ConsistencyChecker(sql, docStore);
            List<ConsistencyChecker.Divergence> initialDivergences = checker.check();
            assertTrue(initialDivergences.isEmpty(), "stores must agree initially: " + initialDivergences);

            // Deliberately alter a document in document store
            NormalizedTxn original = docStore.byMessageId("m-legacy-0001").orElseThrow();
            NormalizedTxn tampered = new NormalizedTxn(
                    original.accountLast4(),
                    original.occurredAt(),
                    original.direction(),
                    new BigDecimal("9999.99"), // tampered amount
                    original.category(),
                    original.merchant(),
                    original.sourceMessageIds());

            docStore.save(tampered);

            List<ConsistencyChecker.Divergence> alteredDivergences = checker.check();
            assertFalse(alteredDivergences.isEmpty(), "checker must detect tampered document");
            boolean foundAmountMismatch = alteredDivergences.stream()
                    .anyMatch(d -> d.what().contains("amount_mismatch") || d.what().contains("category_total_divergence"));
            assertTrue(foundAmountMismatch, "names precise amount or total discrepancy: " + alteredDivergences);
        }
    }

    @Test
    void benchmark100kReportsExaminedVsReturnedMetrics() {
        IndexedDocumentStore benchStore = new IndexedDocumentStore();
        int total = 100_000;
        String[] accounts = {"4821", "9075", "3310"};
        Category[] categories = Category.values();

        for (int i = 0; i < total; i++) {
            String acct = accounts[i % accounts.length];
            int month = (i % 12) + 1;
            int day = (i % 28) + 1;
            int hour = i % 24;
            int minute = i % 60;
            OffsetDateTime at = OffsetDateTime.of(2026, month, day, hour, minute, 0, 0, IST);
            Direction dir = (i % 3 == 0) ? Direction.CREDIT : Direction.DEBIT;
            BigDecimal amt = new BigDecimal(String.format("%.2f", (i % 5000) + 1.50));
            Category cat = categories[i % categories.length];
            benchStore.save(new NormalizedTxn(acct, at, dir, amt, cat, "MERCHANT-" + (i % 100), List.of("m-bench-" + i)));
        }

        // Benchmark Q1: Account Month slice
        List<NormalizedTxn> q1Results = benchStore.forAccountMonth("4821", YearMonth.of(2026, 7));
        long q1Examined = benchStore.lastQ1Examined();
        long q1Returned = benchStore.lastQ1Returned();
        assertEquals(q1Examined, q1Returned, "Index-covered seek guarantees examined == returned");
        assertTrue(q1Returned > 0);

        // Benchmark Q2: Category Totals
        Map<Category, BigDecimal> q2Results = benchStore.categoryTotals("4821");
        long q2Examined = benchStore.lastQ2Examined();
        long q2Returned = benchStore.lastQ2Returned();
        assertEquals(1, q2Examined, "Pre-aggregated document guarantees 1 examined");
        assertEquals(1, q2Returned, "Pre-aggregated document guarantees 1 returned");

        // Benchmark Q3: By Message ID
        Optional<NormalizedTxn> q3Result = benchStore.byMessageId("m-bench-42000");
        assertTrue(q3Result.isPresent());
        long q3Examined = benchStore.lastQ3Examined();
        long q3Returned = benchStore.lastQ3Returned();
        assertEquals(1, q3Examined, "Multikey index guarantees 1 examined");
        assertEquals(1, q3Returned, "Multikey index guarantees 1 returned");

        System.out.printf("100K BENCHMARK RESULTS:%n");
        System.out.printf("  Q1 (Account Month):  Examined = %d, Returned = %d%n", q1Examined, q1Returned);
        System.out.printf("  Q2 (Category Totals): Examined = %d, Returned = %d%n", q2Examined, q2Returned);
        System.out.printf("  Q3 (By Message ID):  Examined = %d, Returned = %d%n", q3Examined, q3Returned);
    }
}
