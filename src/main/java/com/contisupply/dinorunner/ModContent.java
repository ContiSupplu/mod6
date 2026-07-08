package com.contisupply.dinorunner;

import com.contisupply.dinorunner.block.ArcadeMachineBlock;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

/**
 * Registration for the Arcade Machine block and its BlockItem.
 * 1.21.2+ style: block/item settings must carry their own RegistryKey.
 */
public final class ModContent {
	public static final Identifier ARCADE_MACHINE_ID = Identifier.of(DinoRunner.MOD_ID, "arcade_machine");

	public static Block ARCADE_MACHINE;
	public static Item ARCADE_MACHINE_ITEM;

	private ModContent() {
	}

	public static void register() {
		RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, ARCADE_MACHINE_ID);
		ARCADE_MACHINE = Registry.register(Registries.BLOCK, blockKey, new ArcadeMachineBlock(
				AbstractBlock.Settings.create()
						.registryKey(blockKey)
						.strength(2.5f)
						.sounds(BlockSoundGroup.METAL)
						// The screen glow - an arcade cabinet should light up a dark room.
						.luminance(state -> 7)));

		RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, ARCADE_MACHINE_ID);
		ARCADE_MACHINE_ITEM = Registry.register(Registries.ITEM, itemKey, new BlockItem(ARCADE_MACHINE,
				new Item.Settings()
						.registryKey(itemKey)
						.useBlockPrefixedTranslationKey()));

		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL)
				.register(entries -> entries.add(ARCADE_MACHINE_ITEM));
	}
}
