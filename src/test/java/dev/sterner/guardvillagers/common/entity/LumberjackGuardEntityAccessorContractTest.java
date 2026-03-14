package dev.sterner.guardvillagers.common.entity;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LumberjackGuardEntityAccessorContractTest {

    @Test
    void exposesPairedJobPositionAccessorWithBlockPosReturnType() throws NoSuchMethodException {
        Method getter = LumberjackGuardEntity.class.getMethod("getPairedJobPos");

        assertEquals(BlockPos.class, getter.getReturnType());
    }
}
