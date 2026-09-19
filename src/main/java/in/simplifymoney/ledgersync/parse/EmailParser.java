package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Supports alerts from HDFC and ICICI bank email senders.
 */
public final class EmailParser implements MessageParser {

    private static final DateTimeFormatter EMAIL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private static final Pattern PATTERN = Pattern.compile(
            "Date:\\s*(?<date>[^\\r\\n]+).*?"
                    + "Your account ending (?<acct>\\d{4}) has been (?<dir>debited|credited) with\\s*"
                    + "(?:INR|Rs\\.?)\\s*(?<amt>[0-9,]+(?:\\.[0-9]{2})?)\\.\\s*"
                    + "Merchant / Remarks:\\s*(?<merchant>[^\\r\\n]+)",
            Pattern.DOTALL);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel())
                && ("alerts@hdfcbank.net".equals(m.sender()) || "alerts@icicibank.com".equals(m.sender()));
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher matcher = PATTERN.matcher(m.body());
        if (!matcher.find()) return Optional.empty();

        String rawDate = matcher.group("date").trim();
        OffsetDateTime at;
        try {
            at = OffsetDateTime.parse(rawDate, EMAIL_DATE_FORMAT)
                    .withOffsetSameInstant(Dates.IST)
                    .truncatedTo(ChronoUnit.MINUTES);
        } catch (Exception e) {
            return Optional.empty();
        }

        BigDecimal amount = Amounts.first(m.body());
        if (amount == null) return Optional.empty();

        Direction direction = "debited".equals(matcher.group("dir"))
                ? Direction.DEBIT : Direction.CREDIT;

        return Optional.of(new ParsedTxn(
                matcher.group("acct"),
                at,
                direction,
                amount,
                matcher.group("merchant").trim(),
                null,
                m.messageId()));
    }
}
