package com.raoulvdberge.refinedstorage.apiimpl.network.node;

import com.mojang.authlib.GameProfile;
import com.raoulvdberge.refinedstorage.RS;
import com.raoulvdberge.refinedstorage.RSUtils;
import com.raoulvdberge.refinedstorage.api.util.IComparer;
import com.raoulvdberge.refinedstorage.container.slot.SlotFilter;
import com.raoulvdberge.refinedstorage.inventory.ItemHandlerBase;
import com.raoulvdberge.refinedstorage.inventory.ItemHandlerFluid;
import com.raoulvdberge.refinedstorage.inventory.ItemHandlerListenerNetworkNode;
import com.raoulvdberge.refinedstorage.inventory.ItemHandlerUpgrade;
import com.raoulvdberge.refinedstorage.item.ItemUpgrade;
import com.raoulvdberge.refinedstorage.tile.TileConstructor;
import com.raoulvdberge.refinedstorage.tile.config.IComparable;
import com.raoulvdberge.refinedstorage.tile.config.IType;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSkull;
import net.minecraft.block.SoundType;
import net.minecraft.block.state.IBlockState;
import net.minecraft.dispenser.BehaviorDefaultDispenseItem;
import net.minecraft.dispenser.PositionImpl;
import net.minecraft.entity.item.EntityFireworkRocket;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.IItemHandler;

public class NetworkNodeConstructor extends NetworkNode implements IComparable, IType {
    public static final String ID = "constructor";

    private static final String NBT_COMPARE = "Compare";
    private static final String NBT_TYPE = "Type";
    private static final String NBT_DROP = "Drop";

    private static final int BASE_SPEED = 20;

    private ItemHandlerBase itemFilters = new ItemHandlerBase(1, new ItemHandlerListenerNetworkNode(this)) {
        @Override
        protected void onContentsChanged(int slot) {
            super.onContentsChanged(slot);

            item = getStackInSlot(slot).isEmpty() ? null : getStackInSlot(slot).copy();
            block = SlotFilter.getBlockState(world, pos.offset(getDirection()), getStackInSlot(slot));
        }
    };

    private ItemHandlerFluid fluidFilters = new ItemHandlerFluid(1, new ItemHandlerListenerNetworkNode(this));

    private ItemHandlerUpgrade upgrades = new ItemHandlerUpgrade(4, new ItemHandlerListenerNetworkNode(this), ItemUpgrade.TYPE_SPEED, ItemUpgrade.TYPE_CRAFTING, ItemUpgrade.TYPE_STACK);

    private int compare = IComparer.COMPARE_NBT | IComparer.COMPARE_DAMAGE;
    private int type = IType.ITEMS;
    private boolean drop = false;

    private IBlockState block;
    private ItemStack item;

    public NetworkNodeConstructor(World world, BlockPos pos) {
        super(world, pos);
    }

    @Override
    public int getEnergyUsage() {
        return RS.INSTANCE.config.constructorUsage + upgrades.getEnergyUsage();
    }

    @Override
    public void update() {
        super.update();

        if (network != null && canUpdate() && ticks % upgrades.getSpeed(BASE_SPEED, 4) == 0) {
            if (type == IType.ITEMS) {
                if (block != null) {
                    if (drop && item != null) {
                        dropItem();
                    } else {
                        placeBlock();
                    }
                } else if (item != null) {
                    if (item.getItem() == Items.FIREWORKS && !drop) {
                        ItemStack took = network.extractItem(item, 1, false);

                        if (took != null) {
                            world.spawnEntity(new EntityFireworkRocket(world, getDispensePositionX(), getDispensePositionY(), getDispensePositionZ(), took));
                        }
                    } else {
                        dropItem();
                    }
                }
            } else if (type == IType.FLUIDS) {
                FluidStack stack = fluidFilters.getFluidStackInSlot(0);

                if (stack != null && stack.getFluid().canBePlacedInWorld()) {
                    BlockPos front = pos.offset(getDirection());

                    Block block = stack.getFluid().getBlock();

                    if (world.isAirBlock(front) && block.canPlaceBlockAt(world, front)) {
                        FluidStack stored = network.getFluidStorageCache().getList().get(stack, compare);

                        if (stored != null && stored.amount >= Fluid.BUCKET_VOLUME) {
                            FluidStack took = network.extractFluid(stack, Fluid.BUCKET_VOLUME, compare, false);

                            if (took != null) {
                                IBlockState state = block.getDefaultState();

                                if (state.getBlock() == Blocks.WATER) {
                                    state = Blocks.FLOWING_WATER.getDefaultState();
                                } else if (state.getBlock() == Blocks.LAVA) {
                                    state = Blocks.FLOWING_LAVA.getDefaultState();
                                }

                                if (!canPlace(front, state)) {
                                    return;
                                }

                                world.setBlockState(front, state, 1 | 2);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean canPlace(BlockPos pos, IBlockState state) {
        BlockEvent.PlaceEvent e = new BlockEvent.PlaceEvent(new BlockSnapshot(world, pos, state), world.getBlockState(pos), FakePlayerFactory.getMinecraft((WorldServer) world), EnumHand.MAIN_HAND);

        return !MinecraftForge.EVENT_BUS.post(e);
    }

    private void placeBlock() {
        BlockPos front = pos.offset(getDirection());

        if (world.isAirBlock(front) && block.getBlock().canPlaceBlockAt(world, front)) {
            ItemStack took = network.extractItem(itemFilters.getStackInSlot(0), 1, compare, true);

            if (took != null) {
                IBlockState state = block.getBlock().getStateForPlacement(world, front, getDirection(), 0.5F, 0.5F, 0.5F, took.getMetadata(), FakePlayerFactory.getMinecraft((WorldServer) world), EnumHand.MAIN_HAND);

                if (!canPlace(front, state)) {
                    return;
                }

                took = network.extractItem(itemFilters.getStackInSlot(0), 1, compare, false);

                if (item.getItem() instanceof ItemBlock) {
                    ((ItemBlock) item.getItem()).placeBlockAt(
                        took,
                        FakePlayerFactory.getMinecraft((WorldServer) world),
                        world,
                        front,
                        getDirection(),
                        0,
                        0,
                        0,
                        state
                    );
                } else {
                    world.setBlockState(front, state, 1 | 2);

                    state.getBlock().onBlockPlacedBy(world, front, state, FakePlayerFactory.getMinecraft((WorldServer) world), took);
                }

                // From ItemBlock#onItemUse
                SoundType blockSound = block.getBlock().getSoundType(state, world, pos, null);
                world.playSound(null, front, blockSound.getPlaceSound(), SoundCategory.BLOCKS, (blockSound.getVolume() + 1.0F) / 2.0F, blockSound.getPitch() * 0.8F);

                if (block.getBlock() == Blocks.SKULL) {
                    world.setBlockState(front, world.getBlockState(front).withProperty(BlockSkull.FACING, getDirection()));

                    TileEntity tile = world.getTileEntity(front);

                    if (tile instanceof TileEntitySkull) {
                        TileEntitySkull skullTile = (TileEntitySkull) tile;

                        if (item.getItemDamage() == 3) {
                            GameProfile playerInfo = null;

                            if (item.hasTagCompound()) {
                                NBTTagCompound tag = item.getTagCompound();

                                if (tag.hasKey("SkullOwner", 10)) {
                                    playerInfo = NBTUtil.readGameProfileFromNBT(tag.getCompoundTag("SkullOwner"));
                                } else if (tag.hasKey("SkullOwner", 8) && !tag.getString("SkullOwner").isEmpty()) {
                                    playerInfo = new GameProfile(null, tag.getString("SkullOwner"));
                                }
                            }

                            skullTile.setPlayerProfile(playerInfo);
                        } else {
                            skullTile.setType(item.getMetadata());
                        }

                        Blocks.SKULL.checkWitherSpawn(world, front, skullTile);
                    }

                }
            } else if (upgrades.hasUpgrade(ItemUpgrade.TYPE_CRAFTING)) {
                ItemStack craft = itemFilters.getStackInSlot(0);

                network.getCraftingManager().schedule(craft, 1, compare);
            }
        }
    }

    private void dropItem() {
        ItemStack took = network.extractItem(item, upgrades.getItemInteractCount(), false);

        if (took != null) {
            BehaviorDefaultDispenseItem.doDispense(world, took, 6, getDirection(), new PositionImpl(getDispensePositionX(), getDispensePositionY(), getDispensePositionZ()));
        } else if (upgrades.hasUpgrade(ItemUpgrade.TYPE_CRAFTING)) {
            ItemStack craft = itemFilters.getStackInSlot(0);

            network.getCraftingManager().schedule(craft, 1, compare);
        }
    }

    // From BlockDispenser#getDispensePosition
    private double getDispensePositionX() {
        return (double) pos.getX() + 0.5D + 0.8D * (double) getDirection().getFrontOffsetX();
    }

    // From BlockDispenser#getDispensePosition
    private double getDispensePositionY() {
        return (double) pos.getY() + (getDirection() == EnumFacing.DOWN ? 0.45D : 0.5D) + 0.8D * (double) getDirection().getFrontOffsetY();
    }

    // From BlockDispenser#getDispensePosition
    private double getDispensePositionZ() {
        return (double) pos.getZ() + 0.5D + 0.8D * (double) getDirection().getFrontOffsetZ();
    }

    @Override
    public int getCompare() {
        return compare;
    }

    @Override
    public void setCompare(int compare) {
        this.compare = compare;

        markDirty();
    }

    @Override
    public void read(NBTTagCompound tag) {
        super.read(tag);

        RSUtils.readItems(upgrades, 1, tag);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public NBTTagCompound write(NBTTagCompound tag) {
        super.write(tag);

        RSUtils.writeItems(upgrades, 1, tag);

        return tag;
    }

    @Override
    public NBTTagCompound writeConfiguration(NBTTagCompound tag) {
        super.writeConfiguration(tag);

        tag.setInteger(NBT_COMPARE, compare);
        tag.setInteger(NBT_TYPE, type);
        tag.setBoolean(NBT_DROP, drop);

        RSUtils.writeItems(itemFilters, 0, tag);
        RSUtils.writeItems(fluidFilters, 2, tag);

        return tag;
    }

    @Override
    public void readConfiguration(NBTTagCompound tag) {
        super.readConfiguration(tag);

        if (tag.hasKey(NBT_COMPARE)) {
            compare = tag.getInteger(NBT_COMPARE);
        }

        if (tag.hasKey(NBT_TYPE)) {
            type = tag.getInteger(NBT_TYPE);
        }

        if (tag.hasKey(NBT_DROP)) {
            drop = tag.getBoolean(NBT_DROP);
        }

        RSUtils.readItems(itemFilters, 0, tag);
        RSUtils.readItems(fluidFilters, 2, tag);
    }

    public boolean isDrop() {
        return drop;
    }

    public void setDrop(boolean drop) {
        this.drop = drop;
    }

    public IItemHandler getUpgrades() {
        return upgrades;
    }

    @Override
    public IItemHandler getDrops() {
        return upgrades;
    }

    @Override
    public boolean hasConnectivityState() {
        return true;
    }

    @Override
    public int getType() {
        return world.isRemote ? TileConstructor.TYPE.getValue() : type;
    }

    @Override
    public void setType(int type) {
        this.type = type;

        markDirty();
    }

    @Override
    public IItemHandler getFilterInventory() {
        return getType() == IType.ITEMS ? itemFilters : fluidFilters;
    }
}