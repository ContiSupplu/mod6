package com.contisupply.fallout.block;

import com.contisupply.fallout.FalloutConfig;
import com.contisupply.fallout.ModContent;
import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;
import org.jetbrains.annotations.Nullable;

/**
 * The Nuke.
 *
 * Arming (both configurable):
 *  - redstone power ({@code triggerByRedstone})
 *  - punching it ({@code triggerByPunch})
 *
 * Once armed it counts down in {@link NukeBlockEntity} with accelerating beeps and a
 * flashing warning lamp (the LIT property swaps the model + emits light).
 * Right-clicking an armed nuke defuses it - your one mercy.
 */
public class NukeBlock extends BlockWithEntity {
	public static final MapCodec<NukeBlock> CODEC = createCodec(NukeBlock::new);

	/** True while the countdown is running (switches to the red warning texture). */
	public static final BooleanProperty PRIMED = BooleanProperty.of("primed");
	/** Flash phase of the warning lamp; toggled in sync with the beeps. */
	public static final BooleanProperty LIT = BooleanProperty.of("lit");

	public NukeBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(PRIMED, false).with(LIT, false));
	}

	@Override
	public MapCodec<NukeBlock> getCodec() {
		return CODEC;
	}

	@Override
	public void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(PRIMED, LIT);
	}

	// BlockWithEntity renders INVISIBLE by default; we want the normal cube model.
	@Override
	public BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new NukeBlockEntity(pos, state);
	}

	@Override
	@Nullable
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		// Countdown only runs server side; clients just see the state flips and hear the beeps.
		return world.isClient() ? null : validateTicker(type, ModContent.NUKE_BLOCK_ENTITY, NukeBlockEntity::serverTick);
	}

	/** Punch to arm ("hitting it" trigger). */
	@Override
	public void onBlockBreakStart(BlockState state, World world, BlockPos pos, PlayerEntity player) {
		if (!world.isClient() && FalloutConfig.get().triggerByPunch && !state.get(PRIMED)) {
			arm(world, pos, state);
			player.sendMessage(Text.literal("Nuke armed. RUN.").formatted(Formatting.RED, Formatting.BOLD), true);
		}
	}

	/** Right click: defuse if armed, otherwise print a hint. */
	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient()) {
			return ActionResult.SUCCESS;
		}
		if (state.get(PRIMED)) {
			disarm(world, pos, state);
			player.sendMessage(Text.literal("Nuke defused. That was close.").formatted(Formatting.GREEN), true);
		} else {
			FalloutConfig cfg = FalloutConfig.get();
			String how = cfg.triggerByRedstone && cfg.triggerByPunch ? "Punch it or power it with redstone"
					: cfg.triggerByRedstone ? "Power it with redstone"
					: cfg.triggerByPunch ? "Punch it"
					: "Triggers are disabled in the config";
			player.sendMessage(Text.literal(how + " to arm. Countdown: " + cfg.countdownSeconds + "s.")
					.formatted(Formatting.YELLOW), true);
		}
		return ActionResult.SUCCESS;
	}

	/** Redstone trigger: fires when a neighbour change powers us... */
	@Override
	public void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock,
			@Nullable WireOrientation wireOrientation, boolean notify) {
		checkRedstone(world, pos, state);
	}

	/** ...and also when the nuke is placed directly onto an already powered spot. */
	@Override
	public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
		if (!oldState.isOf(this)) {
			checkRedstone(world, pos, state);
		}
	}

	private void checkRedstone(World world, BlockPos pos, BlockState state) {
		if (!world.isClient() && FalloutConfig.get().triggerByRedstone
				&& !state.get(PRIMED) && world.isReceivingRedstonePower(pos)) {
			arm(world, pos, state);
		}
	}

	public static void arm(World world, BlockPos pos, BlockState state) {
		world.setBlockState(pos, state.with(PRIMED, true).with(LIT, true), Block.NOTIFY_ALL);
		if (world.getBlockEntity(pos) instanceof NukeBlockEntity nuke) {
			nuke.arm();
		}
		world.playSound(null, pos, SoundEvents.ENTITY_CREEPER_PRIMED, SoundCategory.BLOCKS, 3.0f, 0.5f);
	}

	public static void disarm(World world, BlockPos pos, BlockState state) {
		world.setBlockState(pos, state.with(PRIMED, false).with(LIT, false), Block.NOTIFY_ALL);
		if (world.getBlockEntity(pos) instanceof NukeBlockEntity nuke) {
			nuke.disarm();
		}
		world.playSound(null, pos, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.BLOCKS, 1.5f, 1.4f);
	}
}
