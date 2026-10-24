/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */
// Ported from Baritone (branch 26.2, 9fadf7cf) for Clanker. Changes: package names; reads the server's chunk cache
// instead of the client's, and instead of Baritone's world cache, a search gets a map of the chunks that were loaded
// when it started (captured on the server thread) rather than a thread-safe copy of the client chunk provider.

package io.github.soymods.clanker.server.pathing.util;

import io.github.soymods.clanker.server.pathing.BotContext;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Wraps get for chuck caching capability
 *
 * @author leijurv
 */
public class BlockStateInterface {
    /** Chunks further than this from the bot aren't captured for a search, even if loaded. */
    private static final int MAX_SNAPSHOT_RADIUS = 16;

    private final ServerChunkCache provider;
    private final Long2ObjectMap<LevelChunk> snapshot;
    protected final Level world;
    public final BlockPos.MutableBlockPos isPassableBlockPos;
    public final BlockGetter access;
    public final BetterWorldBorder worldBorder;

    private LevelChunk prev = null;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public BlockStateInterface(BotContext ctx) {
        this(ctx, false);
    }

    /**
     * @param copyLoadedChunks capture the loaded chunks around the bot, so this can be read from a search thread
     */
    public BlockStateInterface(BotContext ctx, boolean copyLoadedChunks) {
        ServerLevel level = ctx.world();
        this.world = level;
        this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("BlockStateInterface must be constructed on the main thread");
        }
        this.provider = level.getChunkSource();
        if (copyLoadedChunks) {
            int radius = Math.min(MAX_SNAPSHOT_RADIUS, level.getServer().getPlayerList().getViewDistance() + 1);
            ChunkPos center = ChunkPos.containing(ctx.player().blockPosition());
            this.snapshot = new Long2ObjectOpenHashMap<>((2 * radius + 1) * (2 * radius + 1));
            for (int cx = center.x() - radius; cx <= center.x() + radius; cx++) {
                for (int cz = center.z() - radius; cz <= center.z() + radius; cz++) {
                    LevelChunk chunk = provider.getChunkNow(cx, cz);
                    if (chunk != null) {
                        snapshot.put(ChunkPos.pack(cx, cz), chunk);
                    }
                }
            }
        } else {
            this.snapshot = null;
        }
        this.isPassableBlockPos = new BlockPos.MutableBlockPos();
        this.access = new BlockStateInterfaceAccessWrapper(this);
    }

    private LevelChunk chunk(int chunkX, int chunkZ) {
        return snapshot != null ? snapshot.get(ChunkPos.pack(chunkX, chunkZ)) : provider.getChunkNow(chunkX, chunkZ);
    }

    public boolean worldContainsLoadedChunk(int blockX, int blockZ) {
        return chunk(blockX >> 4, blockZ >> 4) != null;
    }

    public static Block getBlock(BotContext ctx, BlockPos pos) { // won't be called from the pathing thread because the pathing thread doesn't make a single blockpos pog
        return get(ctx, pos).getBlock();
    }

    public static BlockState get(BotContext ctx, BlockPos pos) {
        return new BlockStateInterface(ctx).get0(pos.getX(), pos.getY(), pos.getZ()); // immense iq
        // can't just do world().get because that doesn't work for out of bounds
        // and toBreak and stuff fails when the movement is instantiated out of load range but it's not able to BlockStateInterface.get what it's going to walk on
    }

    public BlockState get0(BlockPos pos) {
        return get0(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockState get0(int x, int y, int z) { // Mickey resigned
        y -= world.dimensionType().minY();
        // Invalid vertical position
        if (y < 0 || y >= world.dimensionType().height()) {
            return AIR;
        }

        LevelChunk cached = prev;
        // there's great cache locality in block state lookups
        // generally it's within each movement
        // if it's the same chunk as last time
        // we can just skip the mc.world.getChunk lookup
        // which is a Long2ObjectOpenHashMap.get
        // see issue #113
        if (cached != null && cached.getPos().x() == x >> 4 && cached.getPos().z() == z >> 4) {
            return getFromChunk(cached, x, y, z);
        }
        LevelChunk chunk = chunk(x >> 4, z >> 4);
        if (chunk != null && !chunk.isEmpty()) {
            prev = chunk;
            return getFromChunk(chunk, x, y, z);
        }
        return AIR;
    }

    public boolean isLoaded(int x, int z) {
        LevelChunk prevChunk = prev;
        if (prevChunk != null && prevChunk.getPos().x() == x >> 4 && prevChunk.getPos().z() == z >> 4) {
            return true;
        }
        prevChunk = chunk(x >> 4, z >> 4);
        if (prevChunk != null && !prevChunk.isEmpty()) {
            prev = prevChunk;
            return true;
        }
        return false;
    }

    // get the block at x,y,z from this chunk WITHOUT creating a single blockpos object
    public static BlockState getFromChunk(LevelChunk chunk, int x, int y, int z) {
        LevelChunkSection section = chunk.getSections()[y >> 4];
        if (section.hasOnlyAir()) {
            return AIR;
        }
        return section.getBlockState(x & 15, y & 15, z & 15);
    }
}
