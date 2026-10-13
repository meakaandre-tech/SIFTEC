package com.meakaandre.siftec.power;

import com.meakaandre.siftec.building.BuildingIndex;
import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.antarcticgardens.cna.config.CNAConfig;
import org.antarcticgardens.cna.content.electricity.connector.AbstractElectricalConnector;
import org.antarcticgardens.cna.content.electricity.connector.ElectricalConnectorBlockEntity;
import org.antarcticgardens.cna.content.motor.MotorBlockEntity;
import org.antarcticgardens.cna.content.motor.extension.MotorExtensionBlockEntity;
import org.antarcticgardens.cna.energy.EnergyStorage;

import java.util.Map;

/**
 * What a wire connector brings onto its grid:
 * <ul>
 * <li>a connector that is part of a Build Gun building: that building ({@link BuildingPower});</li>
 * <li>a connector on a block that is a {@link PowerMember} itself (the HUB's Biomass Burners): that block;</li>
 * <li>a connector on a New Age block, the way New Age wires it: a motor is a consumer of what it needs to run, a block
 * that only takes energy takes what is spare, and a block that gives energy (carbon brushes) is a generator when the
 * connector is set to pull.</li>
 * </ul>
 */
final class Members {
    private Members() {
    }

    static void resolve(ServerLevel level, AbstractElectricalConnector connector, Map<Object, PowerMember> out) {
        BlockPos pos = connector.getBlockPos();
        BlockPos controller = BuildingIndex.controllerOf(level, pos);
        if (controller != null && level.getBlockEntity(controller) instanceof ControllerBlockEntity c) {
            PowerMember m = BuildingPower.member(level, c);
            if (m != null) out.putIfAbsent(controller, m);
            return;
        }
        BlockPos support = connector.getSupportingBlockPos();
        BlockEntity be = level.getBlockEntity(support);
        if (be instanceof PowerMember m) {
            out.putIfAbsent(support.immutable(), m);
            return;
        }
        if (be == null || be instanceof AbstractElectricalConnector && be != connector) return;
        if (be instanceof MotorBlockEntity motor) {
            out.putIfAbsent(support.immutable(), new Motor(motor));
            return;
        }
        Direction side;
        try {
            side = connector.getFacing();
        } catch (RuntimeException e) {
            side = null;
        }
        EnergyStorage storage = EnergyStorage.findForBlock(level, support, side);
        if (storage == null) return;
        boolean pull = connector instanceof ElectricalConnectorBlockEntity e && e.getMode().pull;
        if (storage.supportsExtraction() && pull) out.putIfAbsent(support.immutable(), new Source(storage));
        else if (storage.supportsInsertion()) out.putIfAbsent(support.immutable(), new Sink(storage));
    }

    /** A New Age motor a player wired up: it asks for what it needs to give its full SU, while it is set to run. */
    static final class Motor implements PowerMember {
        final MotorBlockEntity motor;

        Motor(MotorBlockEntity motor) {
            this.motor = motor;
        }

        private double fullSu() {
            if (motor.isRemoved() || motor.speedBehavior == null || motor.speedBehavior.getValue() == 0 || motor.needsPower != motor.powered) return 0;
            var variant = ((com.meakaandre.siftec.mixin.power.MotorAccessor) motor).siftec$variant();
            float multiplier = 1;
            var level = motor.getLevel();
            Direction facing = motor.getBlockState().getValue(com.zurrtum.create.content.kinetics.base.DirectionalKineticBlock.FACING);
            if (level != null && level.getBlockEntity(motor.getBlockPos().relative(facing.getOpposite())) instanceof MotorExtensionBlockEntity extension
                && extension.getBlockState().getOptionalValue(com.zurrtum.create.content.kinetics.base.DirectionalKineticBlock.FACING).orElse(facing.getOpposite()) == facing) {
                multiplier = extension.getMultiplier();
            }
            return variant.getStress() * multiplier * CNAConfig.getServer().motorSUMultiplier.get();
        }

        @Override
        public double demand() {
            return fullSu();
        }

        @Override
        public void supplied(boolean on) {
            if (!on) return;
            var storage = motor.getEnergyStorage();
            long need = (long) Math.ceil(Power.feOfSu(fullSu()));
            storage.internalInsert(Math.max(0, Math.min(need, storage.getCapacity() - storage.getStoredEnergy())), false);
        }
    }

    /** A New Age block that only takes energy (an energiser, a street light): it gets what is spare. */
    static final class Sink implements PowerMember {
        /** At most this much FE a tick, so a big buffer does not make the generators burn hard for nothing. */
        private static final long MAX_FE = 2048;
        final EnergyStorage storage;

        Sink(EnergyStorage storage) {
            this.storage = storage;
        }

        @Override
        public double sink() {
            return Power.suOfFe(Math.max(0, Math.min(MAX_FE, storage.getCapacity() - storage.getStoredEnergy())));
        }

        @Override
        public void sunk(double su) {
            long fe = (long) Math.floor(Power.feOfSu(su));
            if (fe <= 0) return;
            try (Transaction tx = Transaction.openOuter()) {
                storage.insert(fe, tx);
                tx.commit();
            }
        }
    }

    /** A New Age block that gives energy, wired with a connector set to pull: a generator of what it holds. */
    static final class Source implements PowerMember {
        final EnergyStorage storage;

        Source(EnergyStorage storage) {
            this.storage = storage;
        }

        @Override
        public double capacity() {
            long fe;
            try (Transaction tx = Transaction.openOuter()) {
                fe = storage.extract(Long.MAX_VALUE, tx);
            }
            return Power.suOfFe(fe);
        }

        @Override
        public void generated(double su) {
            long fe = (long) Math.ceil(Power.feOfSu(su));
            if (fe <= 0) return;
            try (Transaction tx = Transaction.openOuter()) {
                storage.extract(fe, tx);
                tx.commit();
            }
        }
    }
}
