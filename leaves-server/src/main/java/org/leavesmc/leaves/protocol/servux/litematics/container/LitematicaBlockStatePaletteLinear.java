package org.leavesmc.leaves.protocol.servux.litematics.container;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class LitematicaBlockStatePaletteLinear implements LitematicaBlockStatePalette {

    private final BlockState[] states;
    private final LitematicaBlockStateContainer blockStateContainer;
    private final int bits;
    private int currentSize;

    public LitematicaBlockStatePaletteLinear(int bitsIn, LitematicaBlockStateContainer blockStateContainer) {
        this.states = new BlockState[1 << bitsIn];
        this.bits = bitsIn;
        this.blockStateContainer = blockStateContainer;
    }

    @Override
    public int idFor(BlockState state) {
        for (int i = 0; i < this.currentSize; ++i) {
            if (this.states[i] == state) {
                return i;
            }
        }

        final int size = this.currentSize;

        if (size < this.states.length) {
            this.states[size] = state;
            ++this.currentSize;
            return size;
        } else {
            return this.blockStateContainer.onResize(this.bits + 1, state);
        }
    }

    @Override
    @Nullable
    public BlockState getBlockState(int indexKey) {
        return indexKey >= 0 && indexKey < this.currentSize ? this.states[indexKey] : null;
    }

    private void requestNewId(BlockState state) {
        final int size = this.currentSize;

        if (size < this.states.length) {
            this.states[size] = state;
            ++this.currentSize;
        }
    }

    @Override
    public void readFromNBT(ListTag tagList) {
        Registry<Block> lookup = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BLOCK);

        final int size = tagList.size();

        for (int i = 0; i < size; ++i) {
            CompoundTag tag = tagList.getCompound(i);
            BlockState state = NbtUtils.readBlockState(lookup, tag);

            if (i > 0 || state != LitematicaBlockStateContainer.AIR_BLOCK_STATE) {
                this.requestNewId(state);
            }
        }
    }

    @Override
    public ListTag writeToNBT() {
        ListTag tagList = new ListTag();

        for (int id = 0; id < this.currentSize; ++id) {
            BlockState state = this.states[id];

            if (state == null) {
                state = LitematicaBlockStateContainer.AIR_BLOCK_STATE;
            }

            CompoundTag tag = NbtUtils.writeBlockState(state);
            tagList.add(tag);
        }

        return tagList;
    }
}
