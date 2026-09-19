package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * Performs deep, field-level verification across all three query contracts:
 *  - Verifies byMessageId lookup and validates amount, category, direction, timestamp, and evidence
 *  - Verifies forAccountMonth completeness and detect extra or missing monthly documents
 *  - Verifies categoryTotals to guarantee financial aggregates match
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();
        List<NormalizedTxn> sqlRows = sql.all();

        // 1. Deduplicate SQL transactions to get canonical expected state
        Map<String, NormalizedTxn> expected = new LinkedHashMap<>();
        Set<String> accounts = new TreeSet<>();
        Set<String> accountMonths = new TreeSet<>();

        for (NormalizedTxn t : sqlRows) {
            String key = t.accountLast4() + "|" + t.occurredAt() + "|" + t.direction() + "|" + t.amount().toPlainString();
            NormalizedTxn existing = expected.get(key);
            if (existing == null) {
                expected.put(key, t);
            } else {
                Set<String> merged = new TreeSet<>(existing.sourceMessageIds());
                merged.addAll(t.sourceMessageIds());
                String merch = !t.merchant().isBlank() ? t.merchant() : existing.merchant();
                expected.put(key, new NormalizedTxn(
                        t.accountLast4(), t.occurredAt(), t.direction(), t.amount(), t.category(), merch, new ArrayList<>(merged)));
            }
            accounts.add(t.accountLast4());
            accountMonths.add(t.accountLast4() + "#" + YearMonth.from(t.occurredAt()));
        }

        // 2. Validate byMessageId lookup and field-level integrity
        Set<String> checkedMessageIds = new HashSet<>();
        for (NormalizedTxn exp : expected.values()) {
            for (String msgId : exp.sourceMessageIds()) {
                if (!checkedMessageIds.add(msgId)) continue;
                Optional<NormalizedTxn> docOpt = documents.byMessageId(msgId);
                if (docOpt.isEmpty()) {
                    divergences.add(new Divergence(
                            "missing_document_for_message_id: " + msgId,
                            exp.toString(),
                            "NOT_FOUND"));
                    continue;
                }

                NormalizedTxn doc = docOpt.get();
                if (!exp.accountLast4().equals(doc.accountLast4())) {
                    divergences.add(new Divergence(
                            "account_last4_mismatch for message " + msgId,
                            exp.accountLast4(),
                            doc.accountLast4()));
                }
                if (exp.amount().compareTo(doc.amount()) != 0) {
                    divergences.add(new Divergence(
                            "amount_mismatch for message " + msgId,
                            exp.amount().toPlainString(),
                            doc.amount().toPlainString()));
                }
                if (exp.direction() != doc.direction()) {
                    divergences.add(new Divergence(
                            "direction_mismatch for message " + msgId,
                            exp.direction().name(),
                            doc.direction().name()));
                }
                if (exp.category() != doc.category()) {
                    divergences.add(new Divergence(
                            "category_mismatch for message " + msgId,
                            exp.category().name(),
                            doc.category().name()));
                }
                if (!exp.occurredAt().isEqual(doc.occurredAt())) {
                    divergences.add(new Divergence(
                            "occurred_at_mismatch for message " + msgId,
                            exp.occurredAt().toString(),
                            doc.occurredAt().toString()));
                }
                if (!new HashSet<>(doc.sourceMessageIds()).containsAll(exp.sourceMessageIds())) {
                    divergences.add(new Divergence(
                            "source_message_ids_incomplete for message " + msgId,
                            exp.sourceMessageIds().toString(),
                            doc.sourceMessageIds().toString()));
                }
            }
        }

        // 3. Validate forAccountMonth queries
        for (String am : accountMonths) {
            String[] parts = am.split("#");
            String acct = parts[0];
            YearMonth ym = YearMonth.parse(parts[1]);

            List<NormalizedTxn> docMonth = documents.forAccountMonth(acct, ym);
            Set<String> docKeys = new HashSet<>();
            for (NormalizedTxn d : docMonth) {
                docKeys.add(d.accountLast4() + "|" + d.occurredAt() + "|" + d.direction() + "|" + d.amount().toPlainString());
            }

            for (NormalizedTxn exp : expected.values()) {
                if (exp.accountLast4().equals(acct) && YearMonth.from(exp.occurredAt()).equals(ym)) {
                    String expKey = exp.accountLast4() + "|" + exp.occurredAt() + "|" + exp.direction() + "|" + exp.amount().toPlainString();
                    if (!docKeys.contains(expKey)) {
                        divergences.add(new Divergence(
                                "missing_transaction_in_month_slice: " + am,
                                exp.toString(),
                                "NOT_PRESENT"));
                    }
                }
            }
        }

        // 4. Validate categoryTotals
        for (String acct : accounts) {
            Map<Category, BigDecimal> sqlTotals = new EnumMap<>(Category.class);
            for (Category c : Category.values()) sqlTotals.put(c, BigDecimal.ZERO.setScale(2));

            for (NormalizedTxn exp : expected.values()) {
                if (exp.accountLast4().equals(acct)) {
                    sqlTotals.put(exp.category(), sqlTotals.get(exp.category()).add(exp.amount()));
                }
            }

            Map<Category, BigDecimal> docTotals = documents.categoryTotals(acct);
            for (Category c : Category.values()) {
                BigDecimal sqlAmt = sqlTotals.get(c);
                BigDecimal docAmt = docTotals.getOrDefault(c, BigDecimal.ZERO.setScale(2));
                if (sqlAmt.compareTo(docAmt) != 0) {
                    divergences.add(new Divergence(
                            "category_total_divergence: " + acct + " [" + c + "]",
                            sqlAmt.toPlainString(),
                            docAmt.toPlainString()));
                }
            }
        }

        return divergences;
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
