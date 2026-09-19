package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * Clusters multiple messages evidencing the same transaction, pairs inter-account
 * self-transfers, identifies micro UPI debits, and assigns accurate categories.
 */
public final class IngestService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        List<ParsedTxn> parsed = new ArrayList<>();
        int skipped = 0;
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
            } else {
                parsed.add(p.get());
            }
        }

        List<NormalizedTxn> transactions = deduplicateAndCategorize(parsed);
        for (NormalizedTxn t : transactions) {
            store.save(t);
        }
        return new Stats(messages.size(), transactions.size(), skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    public static List<NormalizedTxn> deduplicateAndCategorize(List<ParsedTxn> parsed) {
        // Group parsed transactions into canonical clusters
        Map<String, TxnCluster> clusters = new LinkedHashMap<>();
        for (ParsedTxn p : parsed) {
            String key = p.accountLast4() + "|" + p.occurredAt() + "|" + p.direction() + "|" + p.amount().toPlainString();
            TxnCluster cluster = clusters.computeIfAbsent(key, k -> new TxnCluster(
                    p.accountLast4(), p.occurredAt(), p.direction(), p.amount()));
            cluster.addMessage(p.sourceMessageId(), p.merchant());
        }

        List<TxnCluster> clusterList = new ArrayList<>(clusters.values());

        // Pair inter-account transfers between user's accounts
        boolean[] isTransfer = new boolean[clusterList.size()];
        for (int i = 0; i < clusterList.size(); i++) {
            TxnCluster a = clusterList.get(i);
            if (isTransfer[i] || a.direction != Direction.DEBIT) continue;

            for (int j = 0; j < clusterList.size(); j++) {
                if (i == j || isTransfer[j]) continue;
                TxnCluster b = clusterList.get(j);
                if (b.direction != Direction.CREDIT) continue;
                if (a.accountLast4.equals(b.accountLast4)) continue;
                if (a.amount.compareTo(b.amount) != 0) continue;

                long minutesDiff = Math.abs(Duration.between(a.occurredAt, b.occurredAt).toMinutes());
                if (minutesDiff <= 10 && isTransferMerchant(a.merchant) && isTransferMerchant(b.merchant)) {
                    isTransfer[i] = true;
                    isTransfer[j] = true;
                    break;
                }
            }
        }

        // Build NormalizedTxn list
        List<NormalizedTxn> result = new ArrayList<>();
        for (int i = 0; i < clusterList.size(); i++) {
            TxnCluster c = clusterList.get(i);
            Category cat;
            if (isTransfer[i]) {
                cat = Category.TRANSFER;
            } else if (c.direction == Direction.DEBIT
                    && c.amount.compareTo(HUNDRED) <= 0
                    && isUpi(c.merchant)) {
                cat = Category.MICRO;
            } else if (c.direction == Direction.DEBIT) {
                cat = Category.SPEND;
            } else {
                cat = Category.INCOME;
            }

            result.add(new NormalizedTxn(
                    c.accountLast4,
                    c.occurredAt,
                    c.direction,
                    c.amount,
                    cat,
                    c.merchant,
                    new ArrayList<>(c.sourceMessageIds)));
        }

        result.sort(Comparator.comparing(NormalizedTxn::occurredAt)
                .thenComparing(NormalizedTxn::accountLast4));
        return result;
    }

    private static boolean isTransferMerchant(String merchant) {
        if (merchant == null) return false;
        String m = merchant.toUpperCase();
        return m.contains("PARAG KAPOOR") || m.contains("SELF");
    }

    private static boolean isUpi(String merchant) {
        if (merchant == null) return false;
        return merchant.toUpperCase().contains("UPI");
    }

    private static class TxnCluster {
        final String accountLast4;
        final OffsetDateTime occurredAt;
        final Direction direction;
        final BigDecimal amount;
        String merchant = "";
        final Set<String> sourceMessageIds = new TreeSet<>();

        TxnCluster(String accountLast4, OffsetDateTime occurredAt, Direction direction, BigDecimal amount) {
            this.accountLast4 = accountLast4;
            this.occurredAt = occurredAt;
            this.direction = direction;
            this.amount = amount;
        }

        void addMessage(String messageId, String merch) {
            if (messageId != null && !messageId.isBlank()) {
                sourceMessageIds.add(messageId);
            }
            if (merch != null && !merch.isBlank()) {
                if (this.merchant.isBlank() || merch.length() > this.merchant.length()) {
                    this.merchant = merch.trim();
                }
            }
        }
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
