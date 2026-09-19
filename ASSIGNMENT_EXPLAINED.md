# Simplify Money — Backend Engineering Assignment Guide
### *Comprehensive Dual-Perspective Breakdown: Simple English & Deep Technical Architecture*

---

## Part I: The Big Picture (In Simple English)

### 1. What is Simplify Money?
Imagine you make 10 to 30 payments a day: buying tea, paying for groceries on UPI, ordering food on Swiggy, paying electricity bills, or receiving your salary. 
Every time money moves, your bank sends you:
1. An **SMS** (e.g., *"Rs. 15 debited from a/c \*\*4821 to UPI/CHAIWALA. Avl Bal: Rs. 42,000"*), or
2. An **Email** with an alert.

**Simplify Money** is an app on your phone that reads these messages and automatically creates a neat, accurate digital **passbook (ledger)**. You don't have to manually enter anything. You can open the app and instantly see:
- Exactly how much you spent this month.
- Exactly how much money came in.
- Where your money went (shopping, bills, food, tea).

### 2. What is the Problem We Are Solving?
Financial messages from banks are messy, incomplete, and chaotic:
- **Banks format messages differently**: HDFC writes SMS one way; ICICI writes another way; emails look completely different.
- **Duplicate alerts**: If you spend ₹500 on Amazon, HDFC might send you an SMS *and* an email. If an app counts both, you think you spent ₹1,000 when you only spent ₹500!
- **Self-transfers aren't expenses**: If you transfer ₹5,000 from your HDFC account to your ICICI account, no money actually left you. If an app treats that as "₹5,000 spent" and "₹5,000 income", your financial statistics become fake and inflated.
- **Spam and Phishing**: Scammers send fake SMS saying *"Verify PAN to avoid debit of Rs. 5,000"*, or food apps say *"Your delivery is 5 mins away"*. The app must be smart enough to ignore non-bank noise.
- **Tiny transactions clutter everything**: 50 tea payments of ₹10 clutter your screen. These should be rolled up into a "Micro" spending summary.
- **System Glitches (The Incident)**: A customer spent ₹5 on a water can, but the app told them they spent **₹92,213.10** because it read their bank balance instead of their transaction amount!

### 3. What Does This Assignment Ask Us To Do?
The company hands us an unfinished Java backend service that currently fails on real data. We have 48 hours to:
1. **Task 0 (Real World Experience)**: Install the app, refer 3 people, and get brutal, honest feedback on what feels broken or confusing.
2. **Task 1 (App Teardown)**: Connect our real bank messages to the app's "Track" screen, take screenshots, find transactions it got wrong, evaluate user trust, and propose 3 improvements.
3. **Task 2 (Core Ledger Engine)**: Build the Java engine that reads 522 real SMS/emails, ignores spam, merges duplicates, links self-transfers, rolls up micro-spends ($\le ₹100$), and outputs 3 clean JSON files (`ledger.json`, `summary.json`, `reconciliation.json`).
4. **Task 3 (Fix The Live Incident)**: Reproduce the ₹92,213.10 water-can bug, fix the regex in `Amounts.java`, calculate how many other messages were broken, write a regression unit test, and post a 5-line incident note.
5. **Task 4 (Move to Modern Document Database)**: Move the ledger from old SQL (H2) to a containerized document database (MongoDB via Docker), make lookups instant at 100,000 transactions with zero slow scans, write a backfill script that cleans dirty SQL data, and write a consistency checker to catch tampered records.

---

## Part II: Deep Technical Breakdown & Specifications

```
                           ┌───────────────────────────┐
                           │   fixtures/corpus-a.jsonl │  (522 Raw Phone Uploads)
                           └─────────────┬─────────────┘
                                         │
                                         ▼
                           ┌───────────────────────────┐
                           │       Parsers.java        │  (Regex & Header Parsing)
                           ├───────────────────────────┤
                           │ - EmailParser (HDFC/ICICI)│
                           │ - HdfcSmsParser (V1/V2/CC)│
                           │ - IciciSmsParser (V1/V2)  │
                           │ - Noise & Spam Filter     │
                           └─────────────┬─────────────┘
                                         │  (Emits ParsedTxn stream)
                                         ▼
                           ┌───────────────────────────┐
                           │     IngestService.java    │  (Pipeline Intelligence)
                           ├───────────────────────────┤
                           │ - Canonical Clustering    │  (Deduplication)
                           │ - Evidence Set Merge      │  (source_message_ids)
                           │ - Transfer Pairing (5m)   │  (4821 <--> 9075)
                           │ - Micro Classification    │  (UPI <= 100)
                           └─────────────┬─────────────┘
                                         │
                  ┌──────────────────────┴──────────────────────┐
                  ▼                                             ▼
     ┌──────────────────────────┐                  ┌──────────────────────────┐
     │       Reports.java       │                  │ IndexedDocumentStore     │
     ├──────────────────────────┤                  │   (MongoDB 7.0 Docker)   │
     │ 1. ledger.json           │                  ├──────────────────────────┤
     │ 2. summary.json          │                  │ Q1: forAccountMonth      │
     │ 3. reconciliation.json   │                  │ Q2: categoryTotals       │
     └──────────────────────────┘                  │ Q3: byMessageId          │
                                                   └──────────────────────────┘
```

---

### Task 0: Community, Profile & Feedback (Mandatory Prerequisite)

- **Objective**: Step out of code and into the shoes of a real user.
- **Action Items**:
  1. Download Simplify Money from the iOS App Store or Google Play Store and complete onboarding.
  2. Share referral links with 3 real users (friends, family, colleagues).
  3. Collect unvarnished, critical feedback: What was confusing? Did SMS sync feel invasive? Was transaction categorization accurate?
  4. Follow Simplify Money on social media ([LinkedIn](https://www.linkedin.com/company/simplify-money/), [Instagram](https://www.instagram.com/simplifymoney.ai), [YouTube](https://www.youtube.com/@simplify_money)).
- **Deliverable**: A 1-page PDF artifact (`TASK_0_FEEDBACK.pdf`) with referral screenshots, social follow proof, and candid user quotes.

---

### Task 1: Product Teardown — The "Track" Screen

- **Objective**: Audit the flagship feature ("Track") in the live mobile application.
- **Action Items**:
  1. **Lifecycle Walkthrough**: Capture screenshots across the 3 fundamental states:
     - *Permission Stage*: How SMS/Email read permissions are requested and explained.
     - *Syncing Stage*: How background progress, latency, and on-device parsing are communicated.
     - *Ledger List Stage*: How dates, merchant names, amounts, and account tags are visually displayed.
  2. **Error Case Identification**: Isolate at least **two real transactions** the app got wrong (e.g., miscategorized ATM withdrawal, self-transfer counted twice, promotional message parsed as spend).
  3. **Trust Evaluation**:
     - *Where users trust the app*: Real-time alerts, clean UI, accurate Swiggy/Amazon detection.
     - *Where trust breaks down*: Unexplained balance mismatches, generic labels (`UPI/12345`), ambiguity regarding whether raw SMS leaves the phone.
  4. **Product Recommendations**: Propose 3 concrete product/engineering improvements with rationale.
- **Deliverable**: A 1–2 page teardown document (`TEARDOWN.md` or PDF).

---

### Task 2: Core Build — Accurate Ledger & Reporting Engine

#### 1. Input Contract: `fixtures/corpus-a.jsonl`
- Contains **522 raw JSON lines**. Each represents a single upload from a mobile device:
  ```json
  {
    "message_id": "m-00004-9c11ae",
    "channel": "sms",
    "sender": "AD-HDFCBK-S",
    "received_at": "2026-07-04T07:19:00+05:30",
    "device_id": "dev-3f1a90c47b21",
    "body": "Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161"
  }
  ```
- **Accounts Represented**:
  - `4821`: Primary HDFC Savings Account
  - `9075`: Secondary ICICI Savings Account
  - `3310`: HDFC Credit Card (`x3310` / `**3310`)

#### 2. Parsing Requirements & Noise Filtering
1. **`EmailParser.java`**:
   - Parses RFC 2822 bank alert emails from `alerts@hdfcbank.net` and `alerts@icicibank.com`.
   - **Critical Detail**: Bank emails carry `Date:` headers in UTC (`+0000`). These must be converted to Indian Standard Time (IST, `+05:30`) and truncated to the minute to match corresponding SMS timestamps.
2. **`IciciSmsParser.java`**:
   - Must support both **V1** format (`Acct XX... debited/credited with INR... on dd-MMM-yy... Info: ...`) and **V2** format (`ICICI Bank Acct XX... Dr/Cr INR... on dd-MMM-yyyy...; ... ref no ... BalAvl ...`).
3. **`HdfcSmsParser.java`**:
   - Must parse single-sentence V1, multi-line V2 (`Sent/Received ... On: ... A/c: XX...`), credit card spends (`spent on HDFC Bank Card x3310`), and recurring E-mandate debits (`E-mandate! ... deducted from your HDFC Bank A/c...`).
4. **Hostile & Non-Transaction Filtering**:
   - Exactly **41 messages** in `corpus-a.jsonl` must be rejected:
     - 5 phishing messages from `VK-ICICIB` (*"verify PAN to avoid debit"*).
     - 10 delivery tracking updates from `BP-DELHVY` (Delhivery).
     - 7 delivery tracking updates from `AX-SWGGYX` (Swiggy).
     - 4 promotional loan offers from `VM-ICICIB-T` (*"pre-approved Personal Loan of upto Rs.5,00,000"*).
     - 7 credit card OTP authentication messages (*"OTP for txn of Rs..."*).
     - 8 pure balance notification messages (*"Avl Bal in a/c... is..."*).

#### 3. Ingestion Pipeline Intelligence (`IngestService.java`)
1. **Deduplication via Canonical Clustering**:
   - Multiple messages often evidence the same financial event (e.g., an SMS and an email for the same debit, or carrier SMS retries).
   - Canonical transaction identity:
     $$\text{Key} = \big(\text{accountLast4},\, \text{occurredAt (IST)},\, \text{direction},\, \text{amount}\big)$$
   - When duplicate messages arrive, merge their `message_id` into a sorted `source_message_ids` set without inserting duplicate ledger rows.
2. **Category Classification Rules**:
   - **`TRANSFER`**: Moving money between owned accounts (`4821` $\longleftrightarrow$ `9075`).
     - Detected by pairing a `DEBIT` on Account A with a `CREDIT` on Account B for the identical amount within a 10-minute timestamp window, matching transfer keywords (`IMPS`, `NEFT`, `RTGS`, `P2A`, `SELF`, `OWN`).
     - Both legs are categorized as `TRANSFER`.
   - **`MICRO`**: Any UPI `DEBIT` where $\text{amount} \le ₹100.00$.
   - **`SPEND`**: All remaining `DEBIT` transactions.
   - **`INCOME`**: All remaining `CREDIT` transactions.

#### 4. Required Output Documents (Produced via `report <out-dir>`)

##### Output 1: `ledger.json`
Every transaction appears exactly once, sorted chronologically:
```json
{
  "transactions": [
    {
      "account_last4": "4821",
      "occurred_at": "2026-07-04T20:24:00+05:30",
      "direction": "debit",
      "amount": "2499.50",
      "category": "SPEND",
      "merchant": "AMAZON PAY",
      "source_message_ids": ["m-00087-1a2b3c", "m-00089-77de01"]
    }
  ]
}
```

##### Output 2: `summary.json`
Per-account aggregated totals. `spend` excludes `MICRO` and `TRANSFER`. `income` excludes `TRANSFER`:
```json
{
  "accounts": {
    "4821": {
      "spend": "79568.38",
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
      "spend": "23941.15",
      "income": "0.00",
      "micro_count": 0,
      "micro_total": "0.00",
      "transferred_out": "0.00",
      "transferred_in": "0.00"
    }
  }
}
```

##### Output 3: `reconciliation.json`
Captures any unexplained divergence between ledger transactions and bank balance trails:
```json
{
  "discrepancies": [
    {
      "account_last4": "4821",
      "occurred_at": "2026-07-29T17:06:00+05:30",
      "amount": "7500.00",
      "note": "Missing transaction in corpus: bank stated balance dropped by 7500.00 between 2026-07-29T11:53:00+05:30 and 2026-07-29T17:06:00+05:30 without an evidencing message."
    }
  ]
}
```

> [!IMPORTANT]
> **The ₹7,500.00 Discrepancy Insight**:
> On account `4821`, between 11:53:00 and 17:06:00 on 2026-07-29, the bank's stated balance dropped by **₹7,575.00**, but only one ₹75.00 spend SMS was delivered. Exactly **₹7,500.00 departed the user's account without any message in the corpus**.
> Because the frozen contract [`NormalizedTxn.java`](file:///c:/Users/shiva/ledger-sync-seed/src/main/java/in/simplifymoney/ledgersync/model/NormalizedTxn.java) strictly mandates non-empty `sourceMessageIds`, fabricating a synthetic transaction is prohibited. An honest engineering submission flags this in `reconciliation.json` rather than faking data.

---

### Task 3: Incident Post-Mortem (INC-2026-09-11)

#### 1. The Glitch
A user was charged ₹5 for a water can (`UPI/WATER CAN`), but their ledger showed a debit of **₹92,213.10**.

#### 2. Root Cause
In [`Amounts.java`](file:///c:/Users/shiva/ledger-sync-seed/src/main/java/in/simplifymoney/ledgersync/parse/Amounts.java):
```java
// Faulty code:
Pattern AMOUNT = Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})");
```
The regex strictly required a decimal point followed by two digits (`\\.[0-9]{2}`).
In SMS `m-00004-9c11ae`:
> *"Rs.5 debited from a/c \*\*4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10."*

`Rs.5` has no decimal point. The regex skipped `Rs.5` and matched the next currency string: `Rs.92,213.10` (the customer's Available Balance!).

#### 3. Blast Radius
- **44 messages** had integer amounts.
- **38 transactions** were corrupted by having the customer's balance recorded as spend.
- **2 transactions** (`m-00002-69e4cd` with `INR 45,000` and `m-00043-4add25` with `Rs.2,750`) had no trailing balance and were completely dropped.
- **4 messages** were loan marketing.

#### 4. The Fix & Prevention
- Updated regex to `(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{2})?)` with explicit `setScale(2)`.
- Stripped trailing balance substrings before amount extraction.
- Added regression tests in [`AmountsTest.java`](file:///c:/Users/shiva/ledger-sync-seed/src/test/java/in/simplifymoney/ledgersync/AmountsTest.java).
- Published 5-line incident note in [`incident/INC-2026-09-11.md`](file:///c:/Users/shiva/ledger-sync-seed/incident/INC-2026-09-11.md).

---

### Task 4: Move Ledger to Document Store (MongoDB)

#### 1. Storage Choice: MongoDB 7.0 vs DynamoDB
- **Selected**: MongoDB 7.0 via `docker-compose.yml`.
- **Engineering Justification**:
  - 100% native local execution with zero AWS credentials, IAM roles, or local emulator friction.
  - Native compound indexes with directional sorting (`{ account_last4: 1, month: 1, occurred_at: -1 }`).
  - Native multikey indexing on array elements (`{ source_message_ids: 1 }`).
  - Profiling metrics directly exposed via `explain("executionStats")` reporting `totalDocsExamined` vs `nReturned`.

#### 2. Query Architecture & The 6 Benchmark Numbers (at 100,000 Transactions)

| Query Contract | MongoDB Metric Name | Docs Examined | Docs Returned | Ratio | Optimization Mechanism |
|---|---|---|---|---|---|
| **Q1: `forAccountMonth`** | `totalDocsExamined` vs `nReturned` | **75** | **75** | **1 : 1** | Compound Index Seek (`account_last4 + month + occurred_at DESC`) |
| **Q2: `categoryTotals`** | `totalDocsExamined` vs `nReturned` | **1** | **1** | **1 : 1** | Materialized Summary Point Lookup (`account_totals` collection) |
| **Q3: `byMessageId`** | `totalDocsExamined` vs `nReturned` | **1** | **1** | **1 : 1** | Multikey BSON Array Index Seek (`source_message_ids: 1`) |

#### 3. Machinery Components
1. **`Backfill.java`**:
   - Migrates historical transactions from legacy SQL (`SqlLedgerStore`) to the Document Store.
   - **Resilience**: The legacy SQL table contains dirty duplicates (`m-legacy-0001` and `m-legacy-0007` in `V2__seed.sql`).
   - `Backfill` hashes canonical keys (`SHA-256`), merges evidence sets (`source_message_ids`), and performs idempotent upserts (`upsert=true`), guaranteeing zero duplicates even across multiple runs or after partial crashes.
2. **`ConsistencyChecker.java`**:
   - Performs bidirectional record-level and field-level validation between SQL and Document Store.
   - Compares: `amount`, `category`, `direction`, `merchant`, `occurred_at`, and `source_message_ids`.
   - Accurately catches tampered values, missing rows, or injected extra documents.

---

## Part III: The Non-Negotiables Checklist

1. **Frozen Files**:
   - `src/main/java/in/simplifymoney/ledgersync/model/NormalizedTxn.java` $\longrightarrow$ **Untouched**
   - `src/main/java/in/simplifymoney/ledgersync/model/Category.java` $\longrightarrow$ **Untouched**
   - `src/test/java/in/simplifymoney/ledgersync/NormalizedTxnContractTest.java` $\longrightarrow$ **Untouched**
2. **Air-Gapped Compilation (`verify.sh`)**:
   - Must compile with `javac` and run purely with JDK 21 standard libraries. No external classpath dependencies in `src/main/java`.
3. **Idempotency**:
   - Ingesting the same file twice or ingesting overlapping files must leave the ledger identical.
4. **Generalization (Unseen Corpus)**:
   - Evaluated on a secret test corpus with different dates, accounts, and user names. Code must never hardcode user names (e.g. "PARAG KAPOOR") or specific accounts.

---

## Part IV: Final Submission Package

To submit to `talent.acquisition@simplifymoney.in`:
- **Subject**: `Simplify Money | Software Engineer/Intern - BE | <Your Name>`
- **Attachments / Links**:
  1. Public GitHub Fork URL (or zipped repository with `.git` history).
  2. Walkthrough Video Link ($\le 5$ minutes on Loom or unlisted YouTube).
  3. Output Reports: `submission/ledger.json`, `summary.json`, `reconciliation.json`.
  4. Incident Note: 5 lines from `incident/INC-2026-09-11.md`.
  5. `README.md` (Setup, Decision Log, Data-driven choices, 6 numbers, AI disclosure, Unfinished list).
  6. `TASK_1_TEARDOWN.pdf` (or `TEARDOWN.md`).
  7. `TASK_0_FEEDBACK.pdf` (Referrals and feedback summary).
  8. Updated Resume / CV.
