package com.contisupply.fallout;

import com.contisupply.fallout.block.NukeBlock;
import com.contisupply.fallout.block.NukeBlockEntity;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSoundGroup;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

/**
 * Registration for the Nuke block, its BlockItem and its block entity.
 * 1.21.2+ style: block/item settings must carry their own RegistryKey.
 */
public final class ModContent {
	public static final Identifier NUKE_ID = Identifier.of(Fallout.MOD_ID, "nuke");

	public static Block NUKE;
	public static Item NUKE_ITEM;
	public static BlockEntityType<NukeBlockEntity> NUKE_BLOCK_ENTITY;

	private ModContent() {
	}

	public static void register() {
		RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, NUKE_ID);
		NUKE = Registry.register(Registries.BLOCK, blockKey, new NukeBlock(
				AbstractBlock.Settings.create()
						.registryKey(blockKey)
						// Sturdy casing: hard-ish to mine, very blast resistant so stray
						// creepers don't delete your shot before you film it.
						.strength(4.0f, 1200.0f)
						.sounds(BlockSoundGroup.METAL)
						// The warning lamp: emits light while the countdown flash is on.
						.luminance(state -> state.get(NukeBlock.LIT) ? 15 : 0)));

		RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, NUKE_ID);
		NUKE_ITEM = Registry.register(Registries.ITEM, itemKey, new BlockItem(NUKE,
				new Item.Settings()
						.registryKey(itemKey)
						.useBlockPrefixedTranslationKey()
						.rarity(Rarity.EPIC)
						.fireproof()));

		NUKE_BLOCK_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, NUKE_ID,
				FabricBlockEntityTypeBuilder.create(NukeBlockEntity::new, NUKE).build());

		ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> entries.add(NUKE_ITEM));
	}
}
