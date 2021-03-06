/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.spi.type;

import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

import static io.prestosql.spi.type.TimestampType.MAX_PRECISION;
import static java.lang.Math.floorMod;
import static java.lang.String.format;

public final class SqlTimestamp
{
    // This needs to be Locale-independent, Java Time's DateTimeFormatter compatible and should never change, as it defines the external API data format.
    public static final String JSON_FORMAT = "uuuu-MM-dd HH:mm:ss[.SSS]";
    public static final DateTimeFormatter JSON_FORMATTER = DateTimeFormatter.ofPattern(JSON_FORMAT);

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private static final int MICROSECONDS_PER_SECOND = 1_000_000;
    private static final int PICOSECONDS_PER_MICROSECOND = 1_000_000;

    private static final long[] POWERS_OF_TEN = {
            1L,
            10L,
            100L,
            1000L,
            10_000L,
            100_000L,
            1_000_000L,
            10_000_000L,
            100_000_000L,
            1_000_000_000L,
            10_000_000_000L,
            100_000_000_000L,
            1000_000_000_000L
    };

    private final int precision;
    private final long epochMicros;
    private final int picosOfMicros;
    private final Optional<TimeZoneKey> sessionTimeZoneKey;

    public static SqlTimestamp fromMillis(int precision, long millis)
    {
        return newInstance(precision, millis * 1000, 0);
    }

    @Deprecated
    public static SqlTimestamp legacyFromMillis(int precision, long millisUtc, TimeZoneKey sessionTimeZoneKey)
    {
        return newLegacyInstance(precision, millisUtc * 1000, 0, sessionTimeZoneKey);
    }

    public static SqlTimestamp newInstance(int precision, long epochMicros, int picosOfMicro)
    {
        return newInstanceWithRounding(precision, epochMicros, picosOfMicro, Optional.empty());
    }

    @Deprecated
    public static SqlTimestamp newLegacyInstance(int precision, long epochMicros, int picosOfMicro, TimeZoneKey sessionTimeZoneKey)
    {
        return newInstanceWithRounding(precision, epochMicros, picosOfMicro, Optional.of(sessionTimeZoneKey));
    }

    private static SqlTimestamp newInstanceWithRounding(int precision, long epochMicros, int picosOfMicro, Optional<TimeZoneKey> sessionTimeZoneKey)
    {
        if (precision < 6) {
            epochMicros = round(epochMicros, 6 - precision);
            picosOfMicro = 0;
        }
        else if (precision == 6) {
            if (round(picosOfMicro, 6) == PICOSECONDS_PER_MICROSECOND) {
                epochMicros++;
            }
            picosOfMicro = 0;
        }
        else {
            picosOfMicro = (int) round(picosOfMicro, 12 - precision);
        }

        return new SqlTimestamp(precision, epochMicros, picosOfMicro, sessionTimeZoneKey);
    }

    private SqlTimestamp(int precision, long epochMicros, int picosOfMicro, Optional<TimeZoneKey> sessionTimeZoneKey)
    {
        this.precision = precision;
        this.epochMicros = epochMicros;
        this.picosOfMicros = picosOfMicro;
        this.sessionTimeZoneKey = sessionTimeZoneKey;
    }

    public int getPrecision()
    {
        return precision;
    }

    public long getMillis()
    {
        checkState(!isLegacyTimestamp(), "getMillis() can be called in new timestamp semantics only");
        return roundDiv(epochMicros, 1000);
    }

    /**
     * @deprecated applicable in legacy timestamp semantics only
     */
    @Deprecated
    public long getMillisUtc()
    {
        checkState(isLegacyTimestamp(), "getMillisUtc() can be called in legacy timestamp semantics only");
        return roundDiv(epochMicros, 1000);
    }

    public long getEpochMicros()
    {
        return epochMicros;
    }

    public long getPicosOfMicros()
    {
        return picosOfMicros;
    }

    /**
     * @deprecated applicable in legacy timestamp semantics only
     */
    @Deprecated
    public Optional<TimeZoneKey> getSessionTimeZoneKey()
    {
        return sessionTimeZoneKey;
    }

    public boolean isLegacyTimestamp()
    {
        return sessionTimeZoneKey.isPresent();
    }

    public SqlTimestamp roundTo(int precision)
    {
        return newInstanceWithRounding(precision, epochMicros, picosOfMicros, sessionTimeZoneKey);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SqlTimestamp that = (SqlTimestamp) o;
        return epochMicros == that.epochMicros &&
                picosOfMicros == that.picosOfMicros &&
                precision == that.precision &&
                sessionTimeZoneKey.equals(that.sessionTimeZoneKey);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(epochMicros, picosOfMicros, precision, sessionTimeZoneKey);
    }

    @JsonValue
    @Override
    public String toString()
    {
        ZoneId zoneId = sessionTimeZoneKey
                .map(TimeZoneKey::getId)
                .map(ZoneId::of)
                .orElse(ZoneOffset.UTC);

        return formatTimestamp(precision, epochMicros, picosOfMicros, zoneId);
    }

    private static String formatTimestamp(int precision, long epochMicros, int picosOfMicro, ZoneId zoneId)
    {
        Instant instant = Instant.ofEpochSecond(Math.floorDiv(epochMicros, MICROSECONDS_PER_SECOND));
        LocalDateTime dateTime = LocalDateTime.ofInstant(instant, zoneId);

        StringBuilder builder = new StringBuilder();
        builder.append(TIMESTAMP_FORMATTER.format(dateTime));
        if (precision > 0) {
            long picoFraction = ((long) floorMod(epochMicros, MICROSECONDS_PER_SECOND)) * PICOSECONDS_PER_MICROSECOND + picosOfMicro;
            long scaledFraction = picoFraction / POWERS_OF_TEN[MAX_PRECISION - precision];
            builder.append(".");
            builder.append(format("%0" + precision + "d", scaledFraction));
        }

        return builder.toString();
    }

    private static long round(long value, int magnitude)
    {
        return roundDiv(value, POWERS_OF_TEN[magnitude]) * POWERS_OF_TEN[magnitude];
    }

    private static long roundDiv(long value, long factor)
    {
        if (value >= 0) {
            return (value + (factor / 2)) / factor;
        }

        return (value + 1 - (factor / 2)) / factor;
    }

    private static void checkState(boolean condition, String message)
    {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
