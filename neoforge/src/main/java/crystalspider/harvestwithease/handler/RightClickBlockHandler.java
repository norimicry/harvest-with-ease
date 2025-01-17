package crystalspider.harvestwithease.handler;

import com.mojang.logging.LogUtils;
import crystalspider.harvestwithease.api.HarvestWithEaseAPI;
import crystalspider.harvestwithease.api.event.HarvestWithEaseEvent.AfterHarvest;
import crystalspider.harvestwithease.api.event.HarvestWithEaseEvent.BeforeHarvest;
import crystalspider.harvestwithease.api.event.HarvestWithEaseEvent.HarvestDrops;
import crystalspider.harvestwithease.api.event.HarvestWithEaseEvent.RightClickHarvestCheck;
import crystalspider.harvestwithease.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.bus.api.Event.Result;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.ToolActions;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.NoSuchElementException;

import static crystalspider.harvestwithease.ModLoader.MOD_ID;
import static net.neoforged.fml.common.Mod.EventBusSubscriber.Bus.FORGE;

/**
 * {@link RightClickBlock} event handler.
 * Handles the {@link RightClickBlock} event with {@link EventPriority#HIGH high priority} to right-click harvest when possible.
 * See {@link #handle(RightClickBlock)} for more details.
 */
@EventBusSubscriber(modid = MOD_ID, bus = FORGE)
public final class RightClickBlockHandler {
  /**
   * Logger.
   */
  private static final Logger LOGGER = LogUtils.getLogger();

  /**
   * Listens and handles the event {@link RightClickBlock} with {@link EventPriority#HIGH high priority}.
   * Will cancel further event processing only if the {@link Player player}
   * is not in spectator mode,
   * is not crouching,
   * is holding the correct item (depends on {@link ModConfig#getRequireHoe() requireHoe})
   * and the interaction involves a fully grown crop.
   * Will also dispatch some events related to right-click harvesting.
   *
   * @param event
   */
  @SubscribeEvent(priority = EventPriority.HIGH)
  private static void handle(RightClickBlock event) {
    Level level = event.getLevel();
    Player player = event.getEntity();
    if (canInteract(player, event)) {
      BlockPos blockPos = event.getPos();
      BlockState blockState = level.getBlockState(blockPos);
      InteractionHand hand = getInteractionHand(player);
      if (hand == event.getHand() && canHarvest(level, blockState, blockPos, player, hand, true)) {
        try {
          IntegerProperty cropAge = HarvestWithEaseAPI.getAge(blockState);
          if (HarvestWithEaseAPI.isMature(blockState, cropAge)) {
            cancel(event);
            if (!level.isClientSide()) {
              harvest((ServerLevel) level, cropAge, blockState, blockPos, event.getFace(), event.getHitVec(), (ServerPlayer) player, hand);
              if (player.getItemInHand(hand).getItem() instanceof TieredItem tool && isHoe(tool.getDefaultInstance()) && HarvestWithEaseAPI.isTierForMultiHarvest(tool)) {
                int fromCenterToEdge = ((HarvestWithEaseAPI.getTierLevel(tool.getTier()) - HarvestWithEaseAPI.getTierLevel(ModConfig.getMultiHarvestStartingTier())) * ModConfig.getAreaIncrementStep().step + ModConfig.getAreaStartingSize().size - 1) / 2;
                BlockPos.betweenClosedStream(AABB.encapsulatingFullBlocks(blockPos, blockPos).inflate(fromCenterToEdge, 0, fromCenterToEdge)).filter(pos -> !pos.equals(blockPos)).forEach(pos -> {
                  BlockState state = level.getBlockState(pos);
                  if (canHarvest(level, state, pos, player, hand, false)) {
                    IntegerProperty age = HarvestWithEaseAPI.getAge(state);
                    if (HarvestWithEaseAPI.isMature(state, age)) {
                      harvest((ServerLevel) level, age, state, pos, event.getFace(), null, (ServerPlayer) player, hand);
                    }
                  }
                });
              }
            }
          }
        } catch (NullPointerException | NoSuchElementException | ClassCastException e) {
          LOGGER.debug("Exception generated by block at [" + blockPos.toShortString() + "]");
          LOGGER.debug("This is a non blocking error, but can result in incorrect behavior for mod " + MOD_ID);
          LOGGER.debug("Most probably the cause of this issue was that a non-crop ID was added in the configuration and its age property could not be retrieved, see stack trace for more details", e);
        }
      }
    }
  }

  /**
   * Harvests the crop, handles all related actions (exp granting, hoe damaging, dropping resources, etc.) and dispatches all related events.
   *
   * @param level {@link ServerLevel level}.
   * @param age {@link IntegerProperty age} of the crop.
   * @param blockState {@link BlockState} of the crop.
   * @param blockPos {@link BlockPos} of the crop.
   * @param face clicked {@link Direction face} of the crop block.
   * @param hitResult {@link BlockHitResult} of the {@link RightClickBlock} event.
   * @param player {@link ServerPlayer player} harvesting the crop.
   * @param hand {@link InteractionHand hand} used to harvest.
   */
  private static void harvest(ServerLevel level, IntegerProperty age, BlockState blockState, BlockPos blockPos, Direction face, BlockHitResult hitResult, ServerPlayer player, InteractionHand hand) {
    NeoForge.EVENT_BUS.post(new BeforeHarvest(level, blockState, blockPos, face, hitResult, player, hand));
    grantExp(player);
    damageHoe(player, hand);
    BlockPos basePos = getBasePos(level, blockState.getBlock(), blockPos);
    updateCrop(level, age, blockState.getBlock(), basePos, player, dropResources(level, level.getBlockState(basePos), basePos, face, hitResult, player, hand));
    playSound(level, player, blockState, blockPos);
    NeoForge.EVENT_BUS.post(new AfterHarvest(level, blockState, blockPos, face, hitResult, player, hand));
  }

  /**
   * Updates the crop in the world, reverting it to age 0 (simulate replanting) and, if it's a multi-block crop, breaks the crop blocks above.
   *
   * @param level {@link ServerLevel level}.
   * @param age {@link IntegerProperty age} of the crop.
   * @param block {@link Block} of the crop clicked.
   * @param basePos {@link BlockPos} of the crop block clicked.
   * @param player {@link ServerPlayer player} harvesting the crop.
   * @param customDrops whether {@link HarvestDrops} listeners have changed the drops to drop.
   */
  private static void updateCrop(ServerLevel level, IntegerProperty age, Block block, BlockPos basePos, ServerPlayer player, boolean customDrops) {
    level.setBlockAndUpdate(basePos, block == Blocks.PITCHER_CROP ? Blocks.AIR.defaultBlockState() : level.getBlockState(basePos).setValue(age, 0));
    if (level.getBlockState(basePos).is(BlockTags.CROPS) && level.getBlockState(basePos.above()).is(block) && !isTallButSeparate(block)) {
      level.destroyBlock(basePos.above(), !customDrops, player);
    }
  }

  /**
   * Returns the base pos of the clicked crop.
   *
   * @param world {@link ServerLevel level}.
   * @param block {@link Block} of the clicked crop.
   * @param blockPos {@link BlockPos} of the crop block clicked.
   * @return the base pos of the clicked crop.
   */
  private static BlockPos getBasePos(ServerLevel world, Block block, BlockPos blockPos) {
    BlockPos basePos;
    for (basePos = blockPos; world.getBlockState(blockPos).is(BlockTags.CROPS) && !isTallButSeparate(block) && world.getBlockState(basePos.below()).is(block); basePos = basePos.below()) ;
    return basePos;
  }

  /**
   * Checks whether the {@link Player} can interact with the {@link RightClickBlock event}.
   *
   * @param player
   * @param event
   * @return whether the {@link Player} can interact with the {@link RightClickBlock event}.
   */
  private static boolean canInteract(Player player, RightClickBlock event) {
    return !player.isSpectator() && event.getUseBlock() != Result.DENY && event.getUseItem() != Result.DENY && event.getResult() != Result.DENY;
  }

  /**
   * Grants the given player the configured amount of experience, if any.
   *
   * @param player {@link ServerPlayer player} to grant the experience to.
   */
  private static void grantExp(ServerPlayer player) {
    if (ModConfig.getGrantedExp() > 0) {
      player.giveExperiencePoints(ModConfig.getGrantedExp());
    }
  }

  /**
   * If needed and possible, damages the hoe of the given {@link ModConfig#getDamageOnHarvest() damage}.
   *
   * @param player {@link ServerPlayer player} holding the hoe.
   * @param hand {@link InteractionHand hand} holding the hoe.
   */
  private static void damageHoe(ServerPlayer player, InteractionHand hand) {
    if (ModConfig.getRequireHoe() && ModConfig.getDamageOnHarvest() > 0 && !player.isCreative()) {
      player.getItemInHand(hand).hurtAndBreak(ModConfig.getDamageOnHarvest(), player, playerEntity -> playerEntity.broadcastBreakEvent(hand));
    }
  }

  /**
   * Drop the resources resulting from harvesting a crop in the given {@link ServerLevel level} and {@link BlockState blockState}, making them pop from the given face and using the item held in the given player hand.
   * Takes care of dispatching the {@link HarvestDrops} to retrieve the drops resulting from the harvest.
   *
   * @param level {@link ServerLevel server level} the drops should come from.
   * @param blockState {@link BlockState state} of the crop being harvested.
   * @param blockPos crop {@link BlockPos position}.
   * @param face {@link Direction face} clicked of the crop.
   * @param hitResult {@link BlockHitResult} of the {@link RightClickBlock} event.
   * @param player {@link ServerPlayer player} harvesting the crop.
   * @param hand {@link InteractionHand hand} used to harvest the crop.
   * @return whether {@link HarvestDrops} listeners have changed the drops to drop.
   */
  private static boolean dropResources(ServerLevel level, BlockState blockState, BlockPos blockPos, Direction face, BlockHitResult hitResult, ServerPlayer player, InteractionHand hand) {
    HarvestDrops event = new HarvestDrops(level, blockState, blockPos, face, hitResult, player, hand);
    NeoForge.EVENT_BUS.post(event);
    for (ItemStack stack : event.drops) {
      if (blockState.getCollisionShape(level, blockPos) != Shapes.empty()) {
        Block.popResourceFromFace(level, blockPos, face, stack);
      } else {
        Block.popResource(level, blockPos, stack);
      }
    }
    return event.haveDropsChanged();
  }

  /**
   * If {@link ModConfig#getPlaySound() playSound} is true, plays the block breaking sound.
   *
   * @param level {@link ServerLevel} to play the sound.
   * @param player {@link ServerPlayer player} activating the sound.
   * @param blockState {@link BlockState state} of the block emitting the sound.
   * @param blockPos {@link BlockPos position} of the block emitting the sound.
   */
  private static void playSound(ServerLevel level, ServerPlayer player, BlockState blockState, BlockPos blockPos) {
    if (ModConfig.getPlaySound()) {
      SoundType soundType = blockState.getBlock().getSoundType(blockState, level, blockPos, player);
      level.playSound(null, blockPos, soundType.getBreakSound(), SoundSource.BLOCKS, soundType.getVolume(), soundType.getPitch());
    }
  }

  /**
   * Cancel the event to avoid further processing.
   *
   * @param event
   */
  private static void cancel(RightClickBlock event) {
    event.setCancellationResult(InteractionResult.SUCCESS);
    event.setCanceled(true);
  }

  /**
   * Returns the most suitable interaction hand from the player.
   * Returns null if there was no suitable interaction hand.
   *
   * @param player
   * @return most suitable interaction hand.
   */
  @Nullable
  private static InteractionHand getInteractionHand(Player player) {
    if (!player.isCrouching()) {
      if (isHoe(player.getMainHandItem())) {
        return InteractionHand.MAIN_HAND;
      }
      if (isHoe(player.getOffhandItem())) {
        return InteractionHand.OFF_HAND;
      }
      if (!ModConfig.getRequireHoe()) {
        return InteractionHand.MAIN_HAND;
      }
    }
    return null;
  }

  /**
   * Checks whether the given itemStack can perform all the {@link ToolActions#DEFAULT_HOE_ACTIONS default hoe actions}.
   *
   * @param handItem
   * @return whether the given itemStack is a hoe tool.
   */
  private static boolean isHoe(ItemStack handItem) {
    return ToolActions.DEFAULT_HOE_ACTIONS.stream().allMatch(handItem::canPerformAction);
  }

  /**
   * Checks whether the given {@link Player} can right-click harvest the crop.
   * Dispatches the {@link RightClickHarvestCheck} event if the right-clicked block is indeed a crop.
   *
   * @param level {@link Level} of the interaction.
   * @param blockState {@link BlockState} of the crop to harvest.
   * @param blockPos {@link BlockPos} of the crop.
   * @param player {@link Player} trying to harvest.
   * @param hand {@link InteractionHand hand} being used to harvest the crop.
   * @param first whether the current crop is the actual right-clicked crop.
   * @return whether the player can right-click harvest the crop.
   */
  private static boolean canHarvest(Level level, BlockState blockState, BlockPos blockPos, Player player, InteractionHand hand, boolean first) {
    if (HarvestWithEaseAPI.isCrop(blockState.getBlock()) && player.hasCorrectToolForDrops(blockState)) {
      RightClickHarvestCheck event = new RightClickHarvestCheck(level, blockState, blockPos, player, hand, true, first);
      NeoForge.EVENT_BUS.post(event);
      return event.canHarvest();
    }
    return false;
  }

  /**
   * Checks whether the given block is something that might be considered a tall crop, but should actually be treated as a normal crop.
   * <p>
   * Currently. the only known crop with this behavior is Farmer's Delight tomatoes.
   *
   * @param block
   * @return whether to treat a tall crop as a normal crop.
   */
  private static boolean isTallButSeparate(Block block) {
    return BuiltInRegistries.BLOCK.getKey(block).toString().equals("farmersdelight:tomatoes");
  }
}
