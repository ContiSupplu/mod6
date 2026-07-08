package com.contisupply.dinorunner.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * The Arcade Machine. Faces you when placed (like a furnace) and opens the Dino Runner
 * game screen when right-clicked.
 *
 * Client/server split: this class is loaded on BOTH sides, so it must never reference a
 * client-only class like MinecraftClient or Screen - doing so crashes dedicated servers.
 * Instead the client entrypoint injects a {@link Runnable} into {@link #clientGameOpener}
 * at client init; on a dedicated server the field simply stays null and right-clicking
 * just swings the arm.
 */
public class ArcadeMachineBlock extends HorizontalFacingBlock {
	public static final MapCodec<ArcadeMachineBlock> CODEC = createCodec(ArcadeMachineBlock::new);

	/** Set by DinoRunnerClient on physical clients; null on dedicated servers. */
	private static Runnable clientGameOpener;

	public ArcadeMachineBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
	}

	public static void setClientGameOpener(Runnable opener) {
		clientGameOpener = opener;
	}

	@Override
	public MapCodec<ArcadeMachineBlock> getCodec() {
		return CODEC;
	}

	@Override
	public void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/** Screen toward the player, like a furnace. */
	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
	}

	/** Right-click: insert coin. The screen only exists client-side. */
	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient() && clientGameOpener != null) {
			clientGameOpener.run();
		}
		return ActionResult.SUCCESS;
	}
}
