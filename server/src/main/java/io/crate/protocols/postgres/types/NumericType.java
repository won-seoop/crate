/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.protocols.postgres.types;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.postgresql.util.ByteConverter;

import io.crate.metadata.RelationLookup;
import io.crate.types.Regproc;
import io.netty.buffer.ByteBuf;

class NumericType extends PGType<BigDecimal> {

    static final int OID = 1700;

    private static final int TYPE_LEN = -1;
    private static final int TYPE_MOD = -1;

    private static final int NUMERIC_DSCALE_MASK = 0x00003FFF;
    private static final short NUMERIC_POS = 0x0000;
    private static final short NUMERIC_NEG = 0x4000;
    private static final int SHORT_BYTES = 2;
    private static final int[] INT_TEN_POWERS = new int[6];
    private static final BigInteger[] BI_TEN_POWERS = new BigInteger[32];
    private static final BigInteger BI_TEN_THOUSAND = BigInteger.valueOf(10000);
    private static final BigInteger BI_MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    static {
        for (int i = 0; i < INT_TEN_POWERS.length; i++) {
            INT_TEN_POWERS[i] = (int) Math.pow(10, i);
        }
        for (int i = 0; i < BI_TEN_POWERS.length; i++) {
            BI_TEN_POWERS[i] = BigInteger.TEN.pow(i);
        }
    }


    public static final NumericType INSTANCE = new NumericType();

    private NumericType() {
        super(OID, TYPE_LEN, TYPE_MOD, "numeric");
    }

    @Override
    public int typArray() {
        return PGArray.NUMERIC_ARRAY.oid();
    }

    @Override
    public String typeCategory() {
        return TypeCategory.NUMERIC.code();
    }

    @Override
    public String type() {
        return Type.BASE.code();
    }

    @Override
    public Regproc typSend() {
        return Regproc.of("numeric_send");
    }

    @Override
    public Regproc typReceive() {
        return Regproc.of("numeric_recv");
    }

    @Override
    public int writeAsBinary(ByteBuf buffer, BigDecimal value) {
        final PositiveShorts shorts = new PositiveShorts();
        BigInteger unscaled = value.unscaledValue().abs();
        int scale = value.scale();
        if (unscaled.equals(BigInteger.ZERO)) {
            final byte[] bytes = new byte[]{0, 0, -1, -1, 0, 0, 0, 0};
            ByteConverter.int2(bytes, 6, Math.max(0, scale));
            buffer.writeInt(bytes.length);
            buffer.writeBytes(bytes);
            // return INT32_BYTE_SIZE + typeLen;
            return bytes.length;
        }
        int weight = -1;
        if (scale <= 0) {
            //this means we have an integer
            //adjust unscaled and weight
            if (scale < 0) {
                scale = Math.abs(scale);
                //weight value covers 4 digits
                weight += scale / 4;
                //whatever remains needs to be incorporated to the unscaled value
                int mod = scale % 4;
                unscaled = unscaled.multiply(tenPower(mod));
                scale = 0;
            }

            while (unscaled.compareTo(BI_MAX_LONG) > 0) {
                final BigInteger[] pair = unscaled.divideAndRemainder(BI_TEN_THOUSAND);
                unscaled = pair[0];
                final short shortValue = pair[1].shortValue();
                if (shortValue != 0 || !shorts.isEmpty()) {
                    shorts.push(shortValue);
                }
                ++weight;
            }
            long unscaledLong = unscaled.longValueExact();
            do {
                final short shortValue = (short) (unscaledLong % 10000);
                if (shortValue != 0 || !shorts.isEmpty()) {
                    shorts.push(shortValue);
                }
                unscaledLong = unscaledLong / 10000L;
                ++weight;
            } while (unscaledLong != 0);
        } else {
            final BigInteger[] split = unscaled.divideAndRemainder(tenPower(scale));
            BigInteger decimal = split[1];
            BigInteger wholes = split[0];
            if (!BigInteger.ZERO.equals(decimal)) {
                int mod = scale % 4;
                int segments = scale / 4;
                if (mod != 0) {
                    decimal = decimal.multiply(tenPower(4 - mod));
                    ++segments;
                }
                do {
                    final BigInteger[] pair = decimal.divideAndRemainder(BI_TEN_THOUSAND);
                    decimal = pair[0];
                    final short shortValue = pair[1].shortValue();
                    if (shortValue != 0 || !shorts.isEmpty()) {
                        shorts.push(shortValue);
                    }
                    --segments;
                } while (!BigInteger.ZERO.equals(decimal));

                //for the leading 0 shorts we either adjust weight (if no wholes)
                // or push shorts
                if (BigInteger.ZERO.equals(wholes)) {
                    weight -= segments;
                } else {
                    //now add leading 0 shorts
                    for (int i = 0; i < segments; i++) {
                        shorts.push((short) 0);
                    }
                }
            }

            while (!BigInteger.ZERO.equals(wholes)) {
                ++weight;
                final BigInteger[] pair = wholes.divideAndRemainder(BI_TEN_THOUSAND);
                wholes = pair[0];
                final short shortValue = pair[1].shortValue();
                if (shortValue != 0 || !shorts.isEmpty()) {
                    shorts.push(shortValue);
                }
            }
        }

        //8 bytes for "header" and then 2 for each short
        final byte[] bytes = new byte[8 + (2 * shorts.size())];
        int idx = 0;

        //number of 2-byte shorts representing 4 decimal digits
        ByteConverter.int2(bytes, idx, shorts.size());
        idx += 2;
        //0 based number of 4 decimal digits (i.e. 2-byte shorts) before the decimal
        ByteConverter.int2(bytes, idx, weight);
        idx += 2;
        //indicates positive, negative or NaN
        ByteConverter.int2(bytes, idx, value.signum() == -1 ? NUMERIC_NEG : NUMERIC_POS);
        idx += 2;
        //number of digits after the decimal
        ByteConverter.int2(bytes, idx, Math.max(0, scale));
        idx += 2;

        short s;
        while ((s = shorts.pop()) != -1) {
            ByteConverter.int2(bytes, idx, s);
            idx += 2;
        }

        buffer.writeInt(bytes.length);
        buffer.writeBytes(bytes);
        return bytes.length;

        //return INT32_BYTE_SIZE + typeLen;
    }

    @Override
    public BigDecimal readBinaryValue(ByteBuf buffer, int numBytes) {
        if (numBytes < 8) {
            throw new IllegalArgumentException("number of bytes should be at-least 8");
        }
        // Number of DEC_DIGIT blocks
        //number of 2-byte shorts representing 4 decimal digits - should be treated as unsigned
        int len = buffer.readShort() & 0xFFFF;
        // DEC_DIGIT blocks before decimal point
        short weight = buffer.readShort();
        short sign = buffer.readShort();
        short scale = buffer.readShort();

        if (numBytes != (len * SHORT_BYTES + 8)) {
            throw new IllegalArgumentException("invalid length of bytes \"numeric\" value");
        }

        if ((scale & NUMERIC_DSCALE_MASK) != scale) {
            throw new IllegalArgumentException("invalid scale in \"numeric\" value");
        }

        if (len == 0) {
            return new BigDecimal(BigInteger.ZERO, scale);
        }

        short d = buffer.readShort();

        //if the absolute value is (0, 1), then leading '0' values
        //do not matter for the unscaledInt, but trailing 0s do
        if (weight < 0) {
            assert scale > 0;
            int effectiveScale = scale;
            //adjust weight to determine how many leading 0s after the decimal
            //before the provided values/digits actually begin
            ++weight;
            if (weight < 0) {
                effectiveScale += 4 * weight;
            }

            int i = 1;
            //typically there should not be leading 0 short values, as it is more
            //efficient to represent that in the weight value
            for (; i < len && d == 0; i++) {
                //each leading 0 value removes 4 from the effective scale
                effectiveScale -= 4;
                d = buffer.readShort();
            }

            assert effectiveScale > 0;
            if (effectiveScale >= 4) {
                effectiveScale -= 4;
            } else {
                //an effective scale of less than four means that the value d
                //has trailing 0s which are not significant
                //so we divide by the appropriate power of 10 to reduce those
                d = (short) (d / INT_TEN_POWERS[4 - effectiveScale]);
                effectiveScale = 0;
            }
            //defer moving to BigInteger as long as possible
            //operations on the long are much faster
            BigInteger unscaledBI = null;
            long unscaledInt = d;
            for (; i < len; i++) {
                if (i == 4 && effectiveScale > 2) {
                    unscaledBI = BigInteger.valueOf(unscaledInt);
                }
                d = buffer.readShort();
                //if effective scale is at least 4, then all 4 digits should be used
                //and the existing number needs to be shifted 4
                if (effectiveScale >= 4) {
                    if (unscaledBI == null) {
                        unscaledInt *= 10000;
                    } else {
                        unscaledBI = unscaledBI.multiply(BI_TEN_THOUSAND);
                    }
                    effectiveScale -= 4;
                } else {
                    //if effective scale is less than 4, then only shift left based on remaining scale
                    if (unscaledBI == null) {
                        unscaledInt *= INT_TEN_POWERS[effectiveScale];
                    } else {
                        unscaledBI = unscaledBI.multiply(tenPower(effectiveScale));
                    }
                    //and d needs to be shifted to the right to only get correct number of
                    //significant digits
                    d = (short) (d / INT_TEN_POWERS[4 - effectiveScale]);
                    effectiveScale = 0;
                }
                if (unscaledBI == null) {
                    unscaledInt += d;
                } else {
                    if (d != 0) {
                        unscaledBI = unscaledBI.add(BigInteger.valueOf(d));
                    }
                }
            }
            //now we need BigInteger to create BigDecimal
            if (unscaledBI == null) {
                unscaledBI = BigInteger.valueOf(unscaledInt);
            }
            //if there is remaining effective scale, apply it here
            if (effectiveScale > 0) {
                unscaledBI = unscaledBI.multiply(tenPower(effectiveScale));
            }
            if (sign == NUMERIC_NEG) {
                unscaledBI = unscaledBI.negate();
            }

            return new BigDecimal(unscaledBI, scale);
        }

        //if there is no scale, then shorts are the unscaled int
        if (scale == 0) {
            //defer moving to BigInteger as long as possible
            //operations on the long are much faster
            BigInteger unscaledBI = null;
            long unscaledInt = d;
            //loop over all of the len shorts to process as the unscaled int
            for (int i = 1; i < len; i++) {
                if (i == 4) {
                    unscaledBI = BigInteger.valueOf(unscaledInt);
                }
                d = buffer.readShort();
                if (unscaledBI == null) {
                    unscaledInt *= 10000;
                    unscaledInt += d;
                } else {
                    unscaledBI = unscaledBI.multiply(BI_TEN_THOUSAND);
                    if (d != 0) {
                        unscaledBI = unscaledBI.add(BigInteger.valueOf(d));
                    }
                }
            }
            //now we need BigInteger to create BigDecimal
            if (unscaledBI == null) {
                unscaledBI = BigInteger.valueOf(unscaledInt);
            }
            if (sign == NUMERIC_NEG) {
                unscaledBI = unscaledBI.negate();
            }
            //the difference between len and weight (adjusted from 0 based) becomes the scale for BigDecimal
            final int bigDecScale = (len - (weight + 1)) * 4;
            //string representation always results in a BigDecimal with scale of 0
            //the binary representation, where weight and len can infer trailing 0s, can result in a negative scale
            //to produce a consistent BigDecimal, we return the equivalent object with scale set to 0
            return bigDecScale == 0 ? new BigDecimal(unscaledBI) : new BigDecimal(unscaledBI, bigDecScale).setScale(0, RoundingMode.HALF_UP);
        }

        //defer moving to BigInteger as long as possible
        //operations on the long are much faster
        BigInteger unscaledBI = null;
        long unscaledInt = d;
        //weight and scale as defined by postgresql are a bit different than how BigDecimal treats scale
        //maintain the effective values to massage as we process through values
        int effectiveWeight = weight;
        int effectiveScale = scale;
        for (int i = 1; i < len; i++) {
            if (i == 4) {
                unscaledBI = BigInteger.valueOf(unscaledInt);
            }
            d = buffer.readShort();
            //first process effective weight down to 0
            if (effectiveWeight > 0) {
                --effectiveWeight;
                if (unscaledBI == null) {
                    unscaledInt *= 10000;
                } else {
                    unscaledBI = unscaledBI.multiply(BI_TEN_THOUSAND);
                }
            } else if (effectiveScale >= 4) {
                //if effective scale is at least 4, then all 4 digits should be used
                //and the existing number needs to be shifted 4
                effectiveScale -= 4;
                if (unscaledBI == null) {
                    unscaledInt *= 10000;
                } else {
                    unscaledBI = unscaledBI.multiply(BI_TEN_THOUSAND);
                }
            } else {
                //if effective scale is less than 4, then only shift left based on remaining scale
                if (unscaledBI == null) {
                    unscaledInt *= INT_TEN_POWERS[effectiveScale];
                } else {
                    unscaledBI = unscaledBI.multiply(tenPower(effectiveScale));
                }
                //and d needs to be shifted to the right to only get correct number of
                //significant digits
                d = (short) (d / INT_TEN_POWERS[4 - effectiveScale]);
                effectiveScale = 0;
            }
            if (unscaledBI == null) {
                unscaledInt += d;
            } else {
                if (d != 0) {
                    unscaledBI = unscaledBI.add(BigInteger.valueOf(d));
                }
            }
        }

        //now we need BigInteger to create BigDecimal
        if (unscaledBI == null) {
            unscaledBI = BigInteger.valueOf(unscaledInt);
        }
        //if there is remaining weight, apply it here
        if (effectiveWeight > 0) {
            unscaledBI = unscaledBI.multiply(tenPower(effectiveWeight * 4));
        }
        //if there is remaining effective scale, apply it here
        if (effectiveScale > 0) {
            unscaledBI = unscaledBI.multiply(tenPower(effectiveScale));
        }
        if (sign == NUMERIC_NEG) {
            unscaledBI = unscaledBI.negate();
        }

        return new BigDecimal(unscaledBI, scale);
    }

    @Override
    protected byte[] encodeAsUTF8Text(BigDecimal value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    BigDecimal decodeUTF8Text(byte[] bytes, RelationLookup relationLookup) {
        return new BigDecimal(new String(bytes, StandardCharsets.UTF_8));
    }

    private static BigInteger tenPower(int exponent) {
        return BI_TEN_POWERS.length > exponent ? BI_TEN_POWERS[exponent] : BigInteger.TEN.pow(exponent);
    }

    /**
     * Simple stack structure for non-negative {@code short} values.
     */
    private static final class PositiveShorts {
        private short[] shorts = new short[8];
        private int idx;

        PositiveShorts() {
        }

        void push(short s) {
            if (s < 0) {
                throw new IllegalArgumentException("only non-negative values accepted: " + s);
            }
            if (idx == shorts.length) {
                grow();
            }
            shorts[idx++] = s;
        }

        int size() {
            return idx;
        }

        boolean isEmpty() {
            return idx == 0;
        }

        short pop() {
            return idx > 0 ? shorts[--idx] : -1;
        }

        private void grow() {
            final int newSize = shorts.length <= 1024 ? shorts.length << 1 : (int) (shorts.length * 1.5);
            shorts = Arrays.copyOf(shorts, newSize);
        }
    }
}
