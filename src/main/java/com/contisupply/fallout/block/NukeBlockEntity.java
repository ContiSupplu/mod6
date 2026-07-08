package com.contisupply.fallout.block;

import com.contisupply.fallout.FalloutConfig;
import com.contisupply.fallout.ModContent;
import com.contisupply.fallout.nuke.DetonationManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Runs the armed countdown.
 *
 * Tension mechanics:
 *  - beeps start slow (1/s) and accelerate to a frantic 10/s in the last two seconds,
 *    with pitch climbing the whole way;
 *  - the warning lamp (LIT property) flashes in sync with the beeps, so the block
 *    visibly strobes and throws light;
 *  - a thin smoke column rises from the casing while it's armed.
 *
 * The countdown persists in NBT, so an armed nuke stays armed across save/load.
 * At zero the block removes itself and hands the position to {@link DetonationManager}.
 */
public class NukeBlockEntity extends BlockEntity {
	/** Ticks until detonation. -1 = idle. */
	private int fuse = -1;
	/** Original fuse length, kept so beep pitch can ramp with overall progress. */
	private int fuseTotal = 1;

	public NukeBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.NUKE_BLOCK_ENTITY, pos, state);
	}

	public void arm() {
		if (fuse < 0) {
			fuseTotal = Math.max(20, FalloutConfig.get().countdownSeconds * 20);
			fuse = fuseTotal;
			markDirty();
		}
	}

	public void disarm() {
		fuse = -1;
		markDirty();
	}

	public boolean isArmed() {
		return fuse >= 0;
	}

	public static void serverTick(World world, BlockPos pos, BlockState state, NukeBlockEntity nuke) {
		if (nuke.fuse < 0 || !(world instanceof ServerWorld serverWorld)) {
			return;
		}

		nuke.fuse--;

		// Beep cadence: 20 ticks apart early, then 10, 5 and finally every 2 ticks.
		int remaining = nuke.fuse;
		int interval = remaining > 200 ? 20 : remaining > 100 ? 10 : remaining > 40 ? 5 : 2;

		if (remaining > 0 && remaining % interval == 0) {
			// Pitch climbs from ~0.7 to 2.0 as the fuse runs out.
			float progress = 1.0f - (float) remaining / (float) nuke.fuseTotal;
			float pitch = 0.7f + 1.3f * progress;
			world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(),
					SoundCategory.BLOCKS, 3.0f, pitch);

			// Flash the warning lamp in sync with the beep.
			if (state.get(NukeBlock.PRIMED)) {
				world.setBlockState(pos, state.with(NukeBlock.LIT, !state.get(NukeBlock.LIT)),
						Block.NOTIFY_LISTENERS);
			}

			// Nervous little smoke column from the casing.
			serverWorld.spawnParticles(ParticleTypes.SMOKE,
					pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
					4, 0.15, 0.1, 0.15, 0.01);
		}

		if (nuke.fuse <= 0) {
			nuke.fuse = -1;
			// Remove the block first (this also discards this block entity),
			// then let the DetonationManager take over from the next world tick.
			world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
			DetonationManager.start(serverWorld, pos);
		}
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		view.putInt("Fuse", fuse);
		view.putInt("FuseTotal", fuseTotal);
	}

	@Override
	protected void readData(ReadView view) {
		super.readData(view);
		fuse = view.getInt("Fuse", -1);
		fuseTotal = Math.max(1, view.getInt("FuseTotal", 1));
	}
}
