# Simplify Money — Ledger Sync: Engineering & Implementation Plan

> **Submission Window:** 48 Hours  
> **Source of Truth:** [`README.md`](README.md), frozen contracts ([`NormalizedTxn.java`](src/main/java/in/simplifymoney/ledgersync/model/NormalizedTxn.java), [`Category.java`](src/main/java/in/simplifymoney/ledgersync/model/Category.java), [`NormalizedTxnContractTest.java`](src/test/java/in/simplifymoney/ledgersync/NormalizedTxnContractTest.java)), [`fixtures/corpus-a-totals.json`](fixtures/corpus-a-totals.json), and [`incident/INC-2026-09-11.md`](incident/INC-2026-09-11.md).

---

## Executive Summary & Architecture Overview

Simplify Money parses bank SMS and email alerts on a user's device to build an accurate, trustworthy financial ledger. The provided codebase is half-finished:
- **Baseline:** 522 raw uploads currently produce 323 un-deduplicated ledger rows; the target checkpoint ([`fixtures/corpus-a-totals.json`](fixtures/corpus-a-totals.json)) expects **257 real transactions**.
- **Live Incident:** A customer was billed ₹92,213.10 for a ₹5 water can due to a flawed amount parser in [`Amounts.java`](src/main/java/in/simplifymoney/ledgersync/parse/Amounts.java).
- **Missing Parsers:** All 56 email messages are dropped by [`EmailParser.java`](src/main/java/in/simplifymoney/ledgersync/parse/EmailParser.java); ICICI V2 SMS alerts fall through [`IciciSmsParser.java`](src/main/java/in/simplifymoney/ledgersync/parse/IciciSmsParser.java).
- **Storage Evolution:** The ledger must transition from legacy H2 SQL ([`SqlLedgerStore.java`](src/main/java/in/simplifymoney/ledgersync/store/SqlLedgerStore.java)) to a containerized Document Store (MongoDB) supporting 3 strict query patterns with zero full-collection scans at 100,000 transactions.

### Recommended Execution Order
$$\text{Task 0 (Manual)} \longrightarrow \text{Task 1 (Teardown)} \longrightarrow \text{Task 3 (Incident Fix)} \longrightarrow \text{Task 2 (Ledger Engine)} \longrightarrow \text{Task 4 (Document Store)}$$
*Note: Task 3 (the incident fix in `Amounts.java`) must precede Task 2, because integer amounts like `Rs.5` or `INR 18,000` otherwise corrupt all category totals and balance reconciliations.*

---

## Task 0: Community, Profile & Feedback (Manual Prerequisite)

*Note: This task is strictly manual and conducted directly by the candidate outside the Java codebase.*

### Task
- Install the Simplify Money app from the app store and complete the personal profile.
- Refer the application to 3 friends/colleagues.
- Collect brutally honest user feedback (specifically focusing on friction, confusion, and features they disliked) and compile a 1-page feedback summary with accompanying screenshots.
- Follow Simplify Money across LinkedIn, Instagram, and YouTube.

### Issue
- Offline backend engineers often optimize code without empathizing with end-user onboarding hurdles, privacy hesitations (SMS permissions), or UI clarity issues.

### Solution
- Candidate conducts live testing with 3 independent users across different phone models and banks.
- Create an honest, unvarnished one-page summary highlighting UI friction points (e.g., sync delays, permission prompts, transaction categorization ambiguity) with annotated screenshots.

### Expected Output (O/P)
- `TASK_0_FEEDBACK.pdf` (or 1-page submission artifact) containing:
  - Screenshots of 3 user referral confirmations.
  - Candid negative and positive feedback quotes.
  - Social media follow confirmations.

---

## Task 1: Product Teardown — The "Track" Screen

### Task
- In the live Simplify Money app, connect a real data source (SMS/email) and navigate to the **Track** screen.
- Observe how the application ingests, extracts, and visualizes financial transactions in real time.
- Author a comprehensive 1–2 page teardown analyzing UX flows, transaction accuracy, user trust boundaries, and recommended product improvements.

### Issue
- Automated financial aggregators face high user skepticism. If an app misinterprets a balance as a spend or counts self-transfers twice, users lose trust immediately.
- Real bank alert variations (varying sender IDs, multi-part SMS, regional language SMS, OTP/promotional noise) frequently cause transaction drops or misclassifications in production apps.

### Solution
1. **Lifecycle Walkthrough:**
   - Document the 3 core stages with full-resolution screenshots:
     1. *Permission Request Stage:* How the app requests `READ_SMS` / email access and explains privacy/local processing.
     2. *Syncing State:* Visual progress indicators, duration, background processing behavior.
     3. *Ledger List State:* How grouped dates, merchant logos, and amounts appear on the Track screen.
2. **Error Case Identification:**
   - Identify at least **two real transactions** the app got wrong or missed (e.g., a peer-to-peer split payment mislabeled as income, an ATM cash withdrawal categorized as shopping, or a promotional offer parsed as an expense).
3. **Trust Evaluation Matrix:**
   - *Where users trust it:* Clean UI, instant notifications, accurate recognition of dominant merchants (Swiggy, Amazon, Uber).
   - *Where trust breaks down:* Opaque balance divergences, missing refund pairings, ambiguous "UPI/Merchant" labels, and broad SMS permission requests without clear on-device guarantees.
4. **Three Actionable Product Enhancements:**
   - *Proposal 1: Local On-Device Parser Guarantee & Indicator:* Visually guarantee to the user that SMS bodies are parsed on-device using a local rule engine, transmitting only normalized hashes to servers.
   - *Proposal 2: Bidirectional Self-Transfer Pairing Card:* When an inter-account transfer is detected, render a unified dual-account card showing `A/c **4821 ➔ A/c **9075` rather than two disconnected entries.
   - *Proposal 3: One-Tap Transaction Rule Correction:* Enable users to reclassify a transaction or adjust merchant rules with instant ledger recalculation.

### Expected Output (O/P)
- A 1–2 page teardown document (`TEARDOWN.md` or PDF):
  - 3 high-quality screenshots for Permission, Sync, and Transaction List.
  - Detailed breakdown of 2 failed/missed transactions with root-cause hypotheses.
  - Trust analysis matrix (Trust Anchors vs. Trust Breakers).
  - 3 justified product/UX architectural enhancements.

---

## Task 3: The Incident (INC-2026-09-11) — The ₹92,213.10 Water Can Spend

### Task
- Resolve open incident [`incident/INC-2026-09-11.md`](incident/INC-2026-09-11.md).
- A customer on account ending `**4821` complained that a ₹5 payment for a water can (`UPI/WATER CAN`) was displayed as a **₹92,213.10** debit.
- Reproduce the incident, isolate the exact root cause (file & line), compute the blast radius across [`fixtures/corpus-a.jsonl`](fixtures/corpus-a.jsonl), implement the fix, add a regression test that fails before the fix and passes after, and write a 5-line incident channel update.

### Issue
1. **Root Cause Analysis:**
   - File: [`src/main/java/in/simplifymoney/ledgersync/parse/Amounts.java`](src/main/java/in/simplifymoney/ledgersync/parse/Amounts.java), Lines 17–18:
     ```java
     private static final Pattern AMOUNT =
             Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})");
     ```
   - The regex strictly requires a period followed by two digits (`\\.[0-9]{2}`).
   - Affected SMS (`m-00004-9c11ae`):
     ```text
     Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161
     ```
   - Because `Rs.5` has no decimal point, `Amounts.first()` skips `Rs.5` and matches the next currency token: `Rs.92,213.10` (the customer's Available Balance!).
   - Consequently, the transaction amount was saved as `92213.10`, causing a massive ledger divergence of `-1,254,130.19`.
2. **Why the Existing Test Suite Was Green:**
   - In [`src/test/java/in/simplifymoney/ledgersync/AmountsTest.java`](src/test/java/in/simplifymoney/ledgersync/AmountsTest.java), all test cases (`readsRupeesWithADot`, `readsInrPrefix`, `readsThousandsSeparators`) strictly tested inputs with two decimal places (`2499.50`, `333.33`, `45000.00`). There was zero test coverage for integer amounts like `Rs.5` or `INR 18000`.
3. **Blast Radius in `fixtures/corpus-a.jsonl`:**
   - **Selection Rule:** A message is corrupted if:
     1. Its transaction amount is an integer rupee figure without paise (`Rs.5`, `INR 18,000`, `INR 4,200`), AND
     2. It is followed by an available balance or credit limit containing two decimals (`Avl Bal: Rs.92,213.10`).
   - **Exact Corpus Count:**
     - **44 total messages** contain integer amounts.
     - **38 transaction messages** suffered balance corruption (transaction amount incorrectly set to the customer's balance).
     - **2 transaction messages** (`m-00002-69e4cd` with `INR 45,000` and `m-00043-4add25` with `Rs.2,750`) had no subsequent balance, causing `Amounts.first()` to return `null` and dropping valid transactions entirely.
     - **4 messages** were non-transaction promotional loan offers (`Rs.5,00,000`).

### Solution
1. **Refactor [`Amounts.java`](src/main/java/in/simplifymoney/ledgersync/parse/Amounts.java):**
   - Update `AMOUNT` pattern to support optional decimals while enforcing proper scale:
     ```java
     private static final Pattern AMOUNT =
             Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{2})?)");
     ```
   - Ensure the transaction amount is extracted from transaction context (or strip the `BALANCE` substring prior to matching the first amount) so balance figures are never matched as transaction amounts.
   - In `toDecimal()`, set scale to 2 using `RoundingMode.UNNECESSARY`:
     ```java
     private static BigDecimal toDecimal(String raw) {
         return new BigDecimal(raw.replace(",", "")).setScale(2, RoundingMode.UNNECESSARY);
     }
     ```
2. **Add Regression Tests in [`AmountsTest.java`](src/test/java/in/simplifymoney/ledgersync/AmountsTest.java):**
   - Test integer amount followed by decimal balance:
     ```java
     @Test
     void readsIntegerAmountBeforeDecimalBalance() {
         assertEquals(new BigDecimal("5.00"),
                 Amounts.first("Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10."));
     }
     ```
   - Test integer amount with INR prefix and thousands separators (`INR 18,000`).
3. **Five-Line Incident Post-Mortem Note:**
   - Compose the required concise 5-line status communication.

### Expected Output (O/P)
- Water can transaction correctly parsed as `₹5.00` debit against merchant `UPI/WATER CAN`.
- Regression test in `AmountsTest.java` failing on old code and passing on new code.
- 5-line incident channel update:
  ```text
  1. What broke: Regex in Amounts.java required two decimal places, skipping integer amounts like Rs.5 and extracting the subsequent balance (Rs.92,213.10) as the transaction amount.
  2. How found: Customer complaint on account **4821 and automated balance divergence alert (divergence=-1,254,130.19).
  3. Who affected: 38 transaction messages in batch b-20260910-2214 were corrupted with balance figures, and 2 transactions were dropped.
  4. Fix applied: Updated Amounts.java regex to accept integer and decimal currency amounts, standardizing to 2 decimal places with scale 2, and isolated balance patterns.
  5. Prevention: Added regression unit tests for integer rupee amounts and enabled balance reconciliation checkpointing in the ingestion pipeline.
  ```

---

## Task 2: Core Build — Accurate Ledger & Financial Reports

### Task
- Ingest `fixtures/corpus-a.jsonl` (522 raw messages) and produce three deterministic JSON documents via `report <out-dir>`:
  1. `ledger.json`: One entry per real transaction (expected: **257 transactions**).
  2. `summary.json`: Per-account totals across categories (`SPEND`, `INCOME`, `MICRO`, `TRANSFER`).
  3. `reconciliation.json`: Explicit reconciliation accounting for opening vs. closing balances.
- Ensure all constraints in [`NormalizedTxn.java`](src/main/java/in/simplifymoney/ledgersync/model/NormalizedTxn.java) are satisfied without modifying frozen classes.

### Issue
1. **Stubbed / Incomplete Parsers:**
   - [`EmailParser.java`](src/main/java/in/simplifymoney/ledgersync/parse/EmailParser.java) throws `UnsupportedOperationException`; drops 56 email messages.
   - [`IciciSmsParser.java`](src/main/java/in/simplifymoney/ledgersync/parse/IciciSmsParser.java) only parses V1 format; misses ICICI V2 messages (`ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; UPI/BARBER... BalAvl Rs 52,841.30`).
2. **No Message Deduplication:**
   - [`IngestService.java`](src/main/java/in/simplifymoney/ledgersync/ingest/IngestService.java) inserts 1 row per message (323 rows). Transactions evidenced by both SMS and email, or carrier retries, are duplicated.
3. **Hostile / Non-Transaction Messages:**
   - Phishing SMS (`VK-ICICIB`: "verify PAN to avoid debit of Rs.5126.00"), delivery updates (`BP-DELHVY`, `AX-SWGGYX`), and bank loan promotions must be rejected.
4. **Naive Categorization:**
   - Direction-only assignment (`DEBIT ➔ SPEND`, `CREDIT ➔ INCOME`). Misses `MICRO` (UPI debits $\le$ ₹100.00) and `TRANSFER` (inter-account self-transfers).
5. **Incomplete Reports:**
   - `Reports.summary()` lumps micro spends and transfers into spend/income.
   - `Reports.reconciliation()` throws `UnsupportedOperationException`.

### Solution
1. **Complete Parser Implementations:**
   - **`EmailParser.java`:**
     - Parse HDFC (`alerts@hdfcbank.net`) and ICICI (`alerts@icicibank.com`) alert emails.
     - Extract RFC 2822 `Date:` header, account last 4, direction (`debited with` / `credited with`), amount, merchant (`Merchant / Remarks:`), and reference number.
     - Register `EmailParser` in `Parsers.java`.
   - **`IciciSmsParser.java`:**
     - Add V2 regex pattern:
       ```java
       Pattern V2 = Pattern.compile(
           "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) (?:INR|Rs\\.?)\\s*(?<amount>[0-9,]+(?:\\.[0-9]{2})?) "
           + "on (?<when>\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}); (?<merchant>.+?) ref no (?<ref>\\w+)\\. "
           + "BalAvl (?:Rs\\.?|INR)\\s*(?<bal>[0-9,]+\\.[0-9]{2})");
       ```
   - **Hostile & Non-Transaction Filtering:**
     - Reject messages where sender is not an approved bank sender.
     - Require explicit debit/credit transactional language; reject phishing warnings and loan offers.
2. **Canonical Deduplication Engine:**
   - Define transaction identity by:
     $$\text{Key} = \big(\text{accountLast4},\, \text{occurredAt (IST)},\, \text{direction},\, \text{amount},\, \text{normalizedMerchant}\big)$$
   - When duplicate or multi-channel messages match the canonical key, merge their `source_message_ids`.
   - Sort `sourceMessageIds` alphabetically in the resulting `NormalizedTxn`.
3. **Category Assignment Logic:**
   - **`MICRO`:** Direction is `DEBIT`, `amount <= 100.00`, and transaction is UPI (`merchant` contains `UPI/` or `UPI`).
   - **`TRANSFER`:** Paired transactions between user's own accounts (`4821` and `9075`) for identical amounts within a 5-minute timestamp window (e.g., `IMPS/P2A/PARAG KAPOOR` or `NEFT INWARD SELF`). Set category to `TRANSFER` on both legs.
   - **`SPEND`:** Remaining `DEBIT` transactions.
   - **`INCOME`:** Remaining `CREDIT` transactions.
4. **Implement Accurate Reports:**
   - **`ledger.json`:** Emits 257 transactions sorted by `occurred_at`.
   - **`summary.json`:**
     - `spend`: Sum of `SPEND` only (excludes `MICRO` and `TRANSFER`).
     - `income`: Sum of `INCOME` only (excludes `TRANSFER`).
     - `micro_count` & `micro_total`: Aggregates for `MICRO`.
     - `transferred_out` & `transferred_in`: Aggregates for `TRANSFER`.
   - **`reconciliation.json`:**
     - Calculate $\text{Derived Closing} = \text{Opening} + \text{Income} + \text{Transferred In} - \text{Spend} - \text{Micro Total} - \text{Transferred Out}$.
     - Compare derived closing balance against stated closing balance from [`fixtures/corpus-a-totals.json`](fixtures/corpus-a-totals.json).
     - If balanced, discrepancies list is `[]`. If un-reconciled, report `account_last4`, `occurred_at`, `amount`, and `note`.

### Expected Output (O/P)
- **`ledger.json`**: Exactly 257 transactions across accounts `4821` (146), `9075` (91), and `3310` (20).
- **`summary.json`**: Exact match with totals:
  ```json
  {
    "accounts": {
      "4821": {
        "spend": "87068.38",
        "income": "101340.83",
        "micro_count": 52,
        "micro_total": "2357.51",
        "transferred_out": "25000.00",
        "transferred_in": "6000.00"
      },
      "9075": {
        "spend": "39058.11",
        "income": "41450.33",
        "micro_count": 45,
        "micro_total": "2086.34",
        "transferred_out": "6000.00",
        "transferred_in": "25000.00"
      },
      "3310": {
        "spend": "...",
        "income": "0.00",
        "micro_count": 0,
        "micro_total": "0.00",
        "transferred_out": "0.00",
        "transferred_in": "0.00"
      }
    }
  }
  ```
- **`reconciliation.json`**: Clean balance verification showing 0.00 divergence for savings accounts.
- **Verification**: `./verify.sh` compiles with zero dependencies and outputs `transactions expected 257, produced 257` with `difference 0.00`.

---

## Task 4: Move Ledger to Document Store (MongoDB)

### Task
- Migrate ledger persistence from SQL ([`SqlLedgerStore.java`](src/main/java/in/simplifymoney/ledgersync/store/SqlLedgerStore.java)) to a Document Store containerized via Docker Compose.
- Implement the 3 query contracts defined in [`DocumentStore.java`](src/main/java/in/simplifymoney/ledgersync/store/DocumentStore.java):
  1. `forAccountMonth(String accountLast4, YearMonth month)`: Newest transactions first.
  2. `categoryTotals(String accountLast4)`: Running totals per category.
  3. `byMessageId(String messageId)`: Transaction lookup by raw message ID.
- Benchmark all 3 queries at **100,000 transactions**, recording **examined vs. returned counts** (6 numbers).
- Implement idempotent [`Backfill.java`](src/main/java/in/simplifymoney/ledgersync/store/Backfill.java) capable of cleaning dirty SQL seeds.
- Implement field-level [`ConsistencyChecker.java`](src/main/java/in/simplifymoney/ledgersync/store/ConsistencyChecker.java) that detects altered documents beyond row counts.

### Issue
1. **Document Store Selection & Justification:**
   - *Choice:* **MongoDB 7.0** via official Docker image (`mongo:7.0`).
   - *Why MongoDB over DynamoDB:* 
     - Fully native local testing without external AWS credentials or third-party wrappers.
     - Rich index support (`compound` and `multikey`) and explicit profiling via `explain("executionStats")` reporting `totalDocsExamined` vs `nReturned`.
     - Direct atomic document updates for maintaining running category totals.
2. **Relational vs. Document Impedance:**
   - In SQL, Q1 requires `WHERE account_last4 = ? AND occurred_at LIKE ? ORDER BY occurred_at DESC`.
   - Q2 in SQL requires `GROUP BY category`, triggering table scans.
   - Q3 in SQL requires `LIKE '%msg_id%'`, causing a full table scan over un-indexed comma-separated strings.
3. **Dirty SQL Legacy State:**
   - [`db/migration/V2__seed.sql`](db/migration/V2__seed.sql) contains duplicate rows (`m-legacy-0001` inserted twice, `m-legacy-0007` inserted twice) and the corrupted water-can record. A naive backfill duplicates rows and fails uniqueness.
4. **Altered Document Detection:**
   - Simple `COUNT(*)` comparisons miss field modifications (e.g. altered amounts, modified categories, dropped message IDs).

### Solution
1. **Docker Compose Setup (`docker-compose.yml`):**
   - Configure MongoDB service exposed on port `27017` with volume persistence.
   - Add MongoDB Java driver (`org.mongodb:mongodb-driver-sync:5.1.0`) to `build.gradle`.
2. **Document Model & Index Design:**
   - **Collection `transactions`:**
     ```json
     {
       "_id": "4821_2026-07-04T20:24:00+05:30_DEBIT_2499.50",
       "account_last4": "4821",
       "occurred_at": "2026-07-04T20:24:00+05:30",
       "month": "2026-07",
       "direction": "DEBIT",
       "amount": "2499.50",
       "category": "SPEND",
       "merchant": "AMAZON PAY",
       "source_message_ids": ["m-00087-1a2b3c", "m-00089-77de01"]
     }
     ```
   - **Collection `account_totals`:**
     ```json
     {
       "_id": "4821",
       "totals": {
         "SPEND": "87068.38",
         "INCOME": "101340.83",
         "MICRO": "2357.51",
         "TRANSFER": "31000.00"
       }
     }
     ```
   - **Indexes:**
     - Q1 Index: `{ account_last4: 1, month: 1, occurred_at: -1 }` $\longrightarrow$ Exact index seek for account and month.
     - Q2 Strategy: Direct point lookup on `_id: account_last4` in `account_totals` $\longrightarrow$ Examined: 1, Returned: 1.
     - Q3 Index: Multikey index on `{ source_message_ids: 1 }` $\longrightarrow$ Direct index seek for message ID.
3. **100,000 Transaction Benchmark Harness:**
   - Generate a deterministic 100,000 transaction dataset into MongoDB.
   - Run each query with `.explain("executionStats")` and capture metrics:
     - **Q1 (Account Month):** `totalDocsExamined: N`, `nReturned: N` (Ratio 1:1, e.g., 75 / 75).
     - **Q2 (Category Totals):** `totalDocsExamined: 1`, `nReturned: 1` (Ratio 1:1).
     - **Q3 (By Message ID):** `totalDocsExamined: 1`, `nReturned: 1` (Ratio 1:1).
4. **Idempotent Backfill Implementation ([`Backfill.java`](src/main/java/in/simplifymoney/ledgersync/store/Backfill.java)):**
   - Read all records from `SqlLedgerStore`.
   - Construct deterministic ID for each transaction: `SHA-256(accountLast4 + "|" + occurredAt + "|" + direction + "|" + amount + "|" + merchant)`.
   - Merge `source_message_ids` for matching transaction keys.
   - Use MongoDB `replaceOne(filter, doc, new ReplaceOptions().upsert(true))` so rerunning after partial failure is completely idempotent.
   - Return `Result(read, written, skipped)`.
5. **Deep Consistency Checker ([`ConsistencyChecker.java`](src/main/java/in/simplifymoney/ledgersync/store/ConsistencyChecker.java)):**
   - Fetch all transactions from SQL and DocumentStore.
   - Perform bidirectional key-based diff:
     - Detect missing transactions in either store.
     - Detect field discrepancies: `amount`, `direction`, `category`, `occurred_at`, `merchant`, and `source_message_ids`.
   - Output exact list of `Divergence(what, inSql, inDocuments)`.

### Expected Output (O/P)
- Working `docker-compose.yml` (`docker compose up -d`).
- Implemented `MongoDocumentStore.java`, `Backfill.java`, and `ConsistencyChecker.java`.
- Verified 6 benchmark numbers at 100,000 scale documented in `README.md`.
- Backfill successfully deduplicating `V2__seed.sql` duplicate entries.
- Consistency test suite verifying detection of altered/tampered document fields.

---

## Deliverables & Submission Checklist

| Deliverable | Description | Location / Target |
|---|---|---|
| **Codebase & Git** | Real commit history showing progressive evolution | Forked Git repo |
| **Reports** | Generated JSON outputs for `corpus-a` | `submission/ledger.json`, `summary.json`, `reconciliation.json` |
| **Incident Post-Mortem** | 5-line incident note & regression test | `incident/INC-2026-09-11.md`, `AmountsTest.java` |
| **Document Store & Metrics** | Docker Compose setup & 6 examined vs returned numbers | `docker-compose.yml`, `README.md` |
| **Decision Log** | 8–10 real engineering decisions, rejected alternatives, rationale | `README.md` |
| **AI Disclosure** | Tools used, evaluation, and 1 concrete AI hallucination/error fixed | `README.md` |
| **Walkthrough Video** | $\le$ 5 minute unlisted video (Loom/YouTube) covering full lifecycle | Submission email link |
| **Task 1 Teardown** | 1–2 page teardown of Track screen with screenshots | `TASK_1_TEARDOWN.pdf` or `TEARDOWN.md` |
| **Task 0 Feedback** | One-pager containing referral proof, candid feedback, screenshots | `TASK_0_FEEDBACK.pdf` |
