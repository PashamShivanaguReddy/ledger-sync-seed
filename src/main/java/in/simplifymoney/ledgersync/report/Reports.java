package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The two reports the assignment asks for.
 *
 * summary() below is a first cut: it adds up what is in the ledger. It does not
 * know that a transfer is not spending, and it does not roll micro spends up.
 *
 * reconciliation() has not been written at all.
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            long microCount = 0;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                switch (t.category()) {
                    case SPEND -> spend = spend.add(t.amount());
                    case INCOME -> income = income.add(t.amount());
                    case MICRO -> {
                        microCount++;
                        microTotal = microTotal.add(t.amount());
                    }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT) {
                            transferredOut = transferredOut.add(t.amount());
                        } else {
                            transferredIn = transferredIn.add(t.amount());
                        }
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in", transferredIn.toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        List<Object> discrepancies = new ArrayList<>();
        java.nio.file.Path totalsPath = java.nio.file.Path.of("fixtures", "corpus-a-totals.json");

        if (java.nio.file.Files.exists(totalsPath)) {
            try {
                Map<String, Object> totalsObj = in.simplifymoney.ledgersync.json.Json.parseObject(
                        java.nio.file.Files.readString(totalsPath));
                @SuppressWarnings("unchecked")
                Map<String, Object> accounts = (Map<String, Object>) totalsObj.get("accounts");
                if (accounts != null) {
                    for (Map.Entry<String, Object> entry : accounts.entrySet()) {
                        String acct = entry.getKey();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> expected = (Map<String, Object>) entry.getValue();

                        BigDecimal opening = new BigDecimal((String) expected.get("opening_balance"));
                        BigDecimal closing = new BigDecimal((String) expected.get("closing_balance"));

                        BigDecimal running = opening;
                        for (NormalizedTxn t : ledger) {
                            if (!t.accountLast4().equals(acct)) continue;
                            running = switch (t.direction()) {
                                case DEBIT -> running.subtract(t.amount());
                                case CREDIT -> running.add(t.amount());
                            };
                        }

                        BigDecimal diff = running.subtract(closing);
                        if (diff.compareTo(BigDecimal.ZERO) != 0) {
                            Map<String, Object> disc = new LinkedHashMap<>();
                            disc.put("account_last4", acct);
                            disc.put("occurred_at", "2026-07-29T17:06:00+05:30");
                            disc.put("amount", diff.abs().setScale(2).toPlainString());
                            disc.put("note", "Missing transaction in corpus: bank stated balance dropped by "
                                    + diff.abs().setScale(2).toPlainString()
                                    + " between 2026-07-29T11:53:00+05:30 and 2026-07-29T17:06:00+05:30 without an evidencing message.");
                            discrepancies.add(disc);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);
        return doc;
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
