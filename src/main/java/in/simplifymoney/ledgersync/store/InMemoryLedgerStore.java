package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Used by SelfCheck and by tests. Keeps unique transactions and merges evidence. */
public final class InMemoryLedgerStore implements LedgerStore {

    private final Map<String, NormalizedTxn> map = new LinkedHashMap<>();

    @Override
    public synchronized void save(NormalizedTxn txn) {
        String key = txn.accountLast4() + "|" + txn.occurredAt() + "|" + txn.direction() + "|" + txn.amount().toPlainString();
        NormalizedTxn existing = map.get(key);
        if (existing == null) {
            map.put(key, txn);
        } else {
            Set<String> mergedIds = new TreeSet<>(existing.sourceMessageIds());
            mergedIds.addAll(txn.sourceMessageIds());
            String merchant = !txn.merchant().isBlank() ? txn.merchant() : existing.merchant();
            map.put(key, new NormalizedTxn(
                    txn.accountLast4(),
                    txn.occurredAt(),
                    txn.direction(),
                    txn.amount(),
                    txn.category(),
                    merchant,
                    new ArrayList<>(mergedIds)));
        }
    }

    @Override
    public synchronized List<NormalizedTxn> all() {
        return Collections.unmodifiableList(new ArrayList<>(map.values()));
    }

    @Override
    public synchronized long count() {
        return map.size();
    }
}
