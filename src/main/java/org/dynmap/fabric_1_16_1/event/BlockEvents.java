package org.dynmap.fabric_1_16_1.event;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldAccess;

public class BlockEvents {
    private BlockEvents() {
    }

    // TODO: implement
    public static Event<BlockCallback> EVENT = EventFactory.createArrayBacked(BlockCallback.class,
            (listeners) -> (event) -> {
                for (BlockCallback callback : listeners) {
                    callback.onBlockEvent(event);
                }
            }
    );

    public interface BlockCallback {
        void onBlockEvent(BlockEvent event);
    }

    public static class BlockEvent {
        private final WorldAccess world;
        private final BlockPos pos;
        private final BlockState state;

        public BlockEvent(WorldAccess world, BlockPos pos, BlockState state) {
            this.pos = pos;
            this.world = world;
            this.state = state;
        }

        public WorldAccess getWorld() {
            return world;
        }

        public BlockPos getPos() {
            return pos;
        }

        public BlockState getState() {
            return state;
        }
    }
}
