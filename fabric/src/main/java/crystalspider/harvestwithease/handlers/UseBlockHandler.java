package crystalspider.harvestwithease.handlers;

import java.util.Collections;
import java.util.NoSuchElementException;

import org.jetbrains.annotations.Nullable;

import crystalspider.harvestwithease.HarvestWithEaseLoader;
import crystalspider.harvestwithease.api.HarvestWithEaseAPI;
import crystalspider.harvestwithease.api.events.HarvestWithEaseEvents;
import crystalspider.harvestwithease.config.HarvestWithEaseConfig;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * {@link UseBlockCallback} event handler.
 * Handles the {@link UseBlockCallback} event to right-click harvest when possible.
 * See {@link #handle(PlayerEntity, World, Hand, BlockHitResult)} for more details.
 */
public class UseBlockHandler {
  /**
   * Handles the event {@link UseBlockCallback}.
   * Will cancel further event processing only if the {@link PlayerEntity player}
   * is not in spectator mode,
   * is not crouching,
   * is holding the correct item (depends on {@link HarvestWithEaseConfig#getRequireHoe() requireHoe})
   * and the interaction involves a fully grown {@link #isCrop crop}.
   * 
   * @param player - {@link PlayerEntity player} executing the action.
   * @param world - {@link World world} where the event is happening.
   * @param hand - {@link Hand hand} player's hand.
   * @param result - {@link BlockHitResult} result of hitting the block.
   * @return - {@link ActionResult} result of the action.
   */
  public static ActionResult handle(PlayerEntity player, World world, Hand hand, BlockHitResult result) {
    ActionResult actionResult = ActionResult.PASS;
    if (!player.isSpectator()) {
      BlockPos blockPos = result.getBlockPos();
      BlockState blockState = world.getBlockState(blockPos);
      if (hand == getInteractionHand(player) && canHarvest(world, blockState, blockPos, player, hand)) {
        try {
          IntProperty age = HarvestWithEaseAPI.getAge(blockState);
          if (blockState.getOrEmpty(age).orElse(0) >= Collections.max(age.getValues())) {
            actionResult = ActionResult.SUCCESS;
            if (!world.isClient()) {
              harvest((ServerWorld) world, age, blockState, blockPos, result.getSide(), result, (ServerPlayerEntity) player, hand);
            }
          }
        } catch (NullPointerException | NoSuchElementException | ClassCastException e) {
          HarvestWithEaseLoader.LOGGER.debug("Exception generated by block at [" + blockPos.getX() + ", " + blockPos.getY() + ", " + blockPos.getZ() + "]");
          HarvestWithEaseLoader.LOGGER.debug("This is a non blocking error, but can result in incorrect behavior for mod " + HarvestWithEaseLoader.MODID);
          HarvestWithEaseLoader.LOGGER.debug("Most probably the cause of this issue was that a non-crop ID was added in the configuration and its age property could not be retrieved, see stack trace for more details", e);
        }
      }
    }
    return actionResult;
  }

  /**
   * Harvests the crop, handles all related actions (exp granting, hoe damaging, dropping resources, etc.) and dispatches all related events.
   * 
   * @param world - {@link ServerWorld world}.
   * @param age - {@link IntProperty age} of the crop.
   * @param blockState - {@link BlockState} of the crop.
   * @param blockPos - {@link BlockPos} of the crop.
   * @param face - clicked {@link Direction face} of the crop block.
   * @param hitResult - {@link BlockHitResult} of the {@link RightClickBlock} event.
   * @param player - {@link ServerPlayerEntity player} harvesting the crop.
   * @param hand - {@link InteractionHand hand} used to harvest.
   */
  private static void harvest(ServerWorld world, IntProperty age, BlockState blockState, BlockPos blockPos, Direction face, BlockHitResult hitResult, ServerPlayerEntity player, Hand hand) {
    HarvestWithEaseEvents.BEFORE_HARVEST.invoker().beforeHarvest(world, blockState, blockPos, face, hitResult, player, hand);
    grantExp(player);
    damageHoe(player, hand);
    dropResources(world, blockState, blockPos, face, hitResult, player, hand);
    world.setBlockState(blockPos, blockState.with(age, 0));
    playSound(world, blockState, blockPos);
    HarvestWithEaseEvents.AFTER_HARVEST.invoker().afterHarvest(world, blockState, blockPos, face, hitResult, player, hand);
  }

  /**
   * Grants the given player the configured amount of experience, if any.
   * 
   * @param player - {@link ServerPlayerEntity player} to grant the experience to.
   */
  private static void grantExp(ServerPlayerEntity player) {
    if (HarvestWithEaseConfig.getGrantedExp() > 0) {
      player.addExperience(HarvestWithEaseConfig.getGrantedExp());
    }
  }

  /**
   * If needed and possible, damages the hoe of the given {@link HarvestWithEaseConfig#getDamageOnHarvest() damage}.
   * 
   * @param player - {@link ServerPlayerEntity player} holding the hoe. 
   * @param hand - {@link Hand hand} holding the hoe.
   */
  private static void damageHoe(ServerPlayerEntity player, Hand hand) {
    if (HarvestWithEaseConfig.getRequireHoe() && HarvestWithEaseConfig.getDamageOnHarvest() > 0 && !player.isCreative()) {
      player.getStackInHand(hand).damage(HarvestWithEaseConfig.getDamageOnHarvest(), player, playerEntity -> playerEntity.sendToolBreakStatus(hand));
    }
  }

  /**
   * Drop the resources resulting from harvesting a crop in the given {@link ServerWorld world} and {@link BlockState blockState}, making them pop from the given face and using the item held in the given player hand.
   * Takes care of dispatching the {@link HarvestWithEaseEvents#HARVEST_DROPS} to retrieve the drops resulting from the harvest.
   * 
   * @param world - {@link ServerWorld server world} the drops should come from.
   * @param blockState - {@link BlockState state} of the crop being harvested.
   * @param blockPos - crop {@link BlockPos position}.
   * @param face - {@link Direction face} clicked of the crop.
   * @param hitResult - {@link BlockHitResult} of the {@link RightClickBlock} event.
   * @param player - {@link ServerPlayer player} harvesting the crop.
   * @param hand - {@link InteractionHand hand} used to harvest the crop.
   */
  private static void dropResources(ServerWorld world, BlockState blockState, BlockPos blockPos, Direction face, BlockHitResult hitResult, ServerPlayerEntity player, Hand hand) {
    for (ItemStack stack : HarvestWithEaseEvents.HARVEST_DROPS.invoker().getDrops(world, blockState, blockPos, face, hitResult, player, hand, new HarvestWithEaseEvents.HarvestDropsEvent(world, blockState, blockPos, player, hand))) {
      Block.dropStack(world, blockPos, face, stack);
    }
  }

  /**
   * If {@link HarvestWithEaseConfig#getPlaySound() playSound} is true, plays the block breaking sound.
   * 
   * @param world - {@link ServerWorld} to play the sound.
   * @param blockState - {@link BlockState state} of the block emitting the sound.
   * @param blockPos - {@link BlockPos position} of the block emitting the sound.
   */
  private static void playSound(ServerWorld world, BlockState blockState, BlockPos blockPos) {
    if (HarvestWithEaseConfig.getPlaySound()) {
      BlockSoundGroup soundGroup = blockState.getBlock().getSoundGroup(blockState);
      world.playSound(null, blockPos, soundGroup.getBreakSound(), SoundCategory.BLOCKS, soundGroup.getVolume(), soundGroup.getPitch());
    }
  }

  /**
   * Returns the most suitable interaction hand from the player.
   * Returns null if there was no suitable interaction hand.
   * 
   * @param player
   * @return most suitable interaction hand.
   */
  @Nullable
  private static Hand getInteractionHand(PlayerEntity player) {
    if (!player.isSneaking()) {
      if (isHoe(player.getStackInHand(Hand.MAIN_HAND))) {
        return Hand.MAIN_HAND;
      }
      if (isHoe(player.getStackInHand(Hand.OFF_HAND))) {
        return Hand.OFF_HAND;
      }
      if (!HarvestWithEaseConfig.getRequireHoe()) {
        return Hand.MAIN_HAND;
      }
    }
    return null;
  }

  /**
   * Checks whether or not the given itemStack is an Item that extends {@link HoeItem}.
   * 
   * @param handItem
   * @return whether the given itemStack is a hoe tool.
   */
  private static boolean isHoe(ItemStack handItem) {
    return handItem.getItem() instanceof HoeItem;
  }

  /**
   * Checks whether the given {@link PlayerEntity} can right-click harvest the crop.
   * Dispatches the {@link HarvestWithEaseEvents#HARVEST_CHECK} event if the right-clicked block is indeed a crop.
   * 
   * @param world - {@link World} of the interaction.
   * @param blockState - {@link BlockState} of the crop to harvest.
   * @param blockPos - {@link BlockPos} of the crop.
   * @param player - {@link PlayerEntity} trying to harvest.
   * @param hand - {@link Hand} being used to harvest the crop.
   * @return whether the player can right-click harvest the crop.
   */
  private static boolean canHarvest(World world, BlockState blockState, BlockPos blockPos, PlayerEntity player, Hand hand) {
    return HarvestWithEaseAPI.isCrop(blockState.getBlock()) && player.canHarvest(blockState) && HarvestWithEaseEvents.HARVEST_CHECK.invoker().check(world, blockState, blockPos, player, hand, new HarvestWithEaseEvents.HarvestCheckEvent());
  }
}
