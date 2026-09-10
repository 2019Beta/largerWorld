package org.devt.largerworld.coordinate;

import java.math.BigDecimal;
import net.minecraft.network.PacketByteBuf;

/** Validates exponent notation before it can expand into a gigantic integer. */
public final class GlobalCoordinateInput {
    private GlobalCoordinateInput() {
    }

    public static BigDecimal parse(String value) {
        BigDecimal coordinate = new BigDecimal(value);
        long integerDigits = (long) coordinate.precision() - coordinate.scale();
        // A cell removes only about six decimal digits from a block coordinate.
        // The exact combined X/Z identifier limit is checked by CellWorldKey.
        if (coordinate.signum() != 0
                && integerDigits > (long) PacketByteBuf.DEFAULT_MAX_STRING_LENGTH + 7) {
            throw new IllegalArgumentException("Global coordinate exceeds Minecraft's dimension packet limit");
        }
        // Tiny exponents should not allocate billions of decimal places during
        // cell division. Local doubles cannot preserve these fractions anyway.
        if (coordinate.signum() == 0 || integerDigits < -324) {
            return BigDecimal.ZERO;
        }
        return coordinate;
    }
}
