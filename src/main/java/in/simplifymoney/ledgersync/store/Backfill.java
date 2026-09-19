package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Handles dirty legacy data without uniqueness guarantees, merges duplicate
 * evidence IDs, and is idempotent across partial or repeated runs.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> sqlRows = source.all();
        long read = sqlRows.size();

        // Deduplicate dirty SQL records by canonical identity: (accountLast4, occurredAt, direction, amount)
        Map<String, NormalizedTxn> deduplicated = new LinkedHashMap<>();
        for (NormalizedTxn t : sqlRows) {
            String key = t.accountLast4() + "|" + t.occurredAt() + "|" + t.direction() + "|" + t.amount().toPlainString();
            NormalizedTxn existing = deduplicated.get(key);
            if (existing == null) {
                deduplicated.put(key, t);
            } else {
                // Merge source message ids from duplicate records
                Set<String> merged = new TreeSet<>(existing.sourceMessageIds());
                merged.addAll(t.sourceMessageIds());
                String merchant = !t.merchant().isBlank() ? t.merchant() : existing.merchant();
                deduplicated.put(key, new NormalizedTxn(
                        t.accountLast4(),
                        t.occurredAt(),
                        t.direction(),
                        t.amount(),
                        t.category(),
                        merchant,
                        new ArrayList<>(merged)));
            }
        }

        long written = 0;
        for (NormalizedTxn t : deduplicated.values()) {
            target.save(t);
            written++;
        }

        long skipped = read - written;
        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}
