package cam72cam.mod.world;

import cam72cam.mod.ModCore;
import cam72cam.mod.event.CommonEvents;
import cam72cam.mod.math.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * Internal, do not use directly
 */
public class ChunkManager {
    private static final TicketType<ChunkPos> UMCTICKET = TicketType.create("universalmodcore", Comparator.comparingLong(ChunkPos::toLong), 20);
    /** Radius of the chunk region pulled in around a kept position. */
    private static final int KEEP_RADIUS = 3;
    /**
     * Ticks a chunk stays force loaded after the last keepLoaded request.  UMCTICKET
     * itself times out after 20 ticks, which is far too short: a car that is
     * momentarily "at rest" stops requesting its chunk, the ticket expires and it
     * drops out of the middle of a moving consist.
     */
    private static final int KEEP_TICKS = 600;
    /**
     * Chunks remembered from the previous session, waiting for the first server tick.
     * They are loaded there (and not in the load event) so the level's chunk source is
     * fully online when we block on them.
     */
    private static final Map<World, List<ChunkPos>> PENDING_LOADS = new WeakHashMap<>();
    /** chunk -> tick of the last keepLoaded request, per world. */
    private static final Map<World, Map<Long, Long>> REMEMBERED = new WeakHashMap<>();

    private static Map<Long, Long> remembered(World world) {
        synchronized (REMEMBERED) {
            return REMEMBERED.computeIfAbsent(world, k -> new HashMap<>());
        }
    }

    /**
     * A region ticket only raises a chunk's ticket level.  The level then propagates
     * one step per tick, so a chunk that is not already loaded can take dozens of
     * ticks to reach FULL.  Callers address entities by UUID and keep their state in
     * memory, so during that window the entity is indistinguishable from a deleted
     * one and the caller rebuilds it from its last saved position.  For a moving
     * consist that is fatal (cars that load late are left behind), so the chunk has
     * to be loaded synchronously in the same tick it is requested.
     */
    private static void loadNow(ServerLevel server, ChunkPos chunkpos) {
        // hasChunk() only reports whether the ticket level asks for a FULL chunk; the
        // chunk itself may still be queued for generation/loading.  Only skip the
        // blocking load when the chunk is really in memory.
        if (server.getChunkSource().getChunkNow(chunkpos.x, chunkpos.z) != null) {
            return;
        }
        server.getChunk(chunkpos.x, chunkpos.z);
    }

    /**
     * Called from the world tick before any mod tick handler runs.  Pulls in the
     * chunks from the previous session in one go so a saved consist is complete
     * before its physics starts moving it.
     */
    static void onWorldTickEarly(World world) {
        if (world.isClient) {
            return;
        }
        ServerLevel server = (ServerLevel) world.internal;
        long now = world.getTicks();

        List<ChunkPos> pending;
        synchronized (PENDING_LOADS) {
            pending = PENDING_LOADS.remove(world);
        }
        if (pending != null && !pending.isEmpty()) {
            int forced = 0;
            for (ChunkPos chunkpos : pending) {
                try {
                    if (server.getChunkSource().getChunkNow(chunkpos.x, chunkpos.z) == null) {
                        forced++;
                    }
                    loadNow(server, chunkpos);
                } catch (RuntimeException e) {
                    ModCore.catching(e);
                }
            }
            ModCore.debug("[UMCDBG] restored %s remembered chunks (%s loaded now)", pending.size(), forced);
        }

        /*
         * The mod only refreshes its tickets while a stock is dirty, and the ticket
         * type expires.  Keep the whole remembered footprint alive from here instead,
         * and forget a chunk only once nobody has asked for it for KEEP_TICKS.
         */
        Map<Long, Long> keeping = remembered(world);
        int kept = 0;
        int dropped = 0;
        for (Iterator<Map.Entry<Long, Long>> it = keeping.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> entry = it.next();
            ChunkPos chunkpos = new ChunkPos(entry.getKey());
            if (now - entry.getValue() > KEEP_TICKS) {
                server.getChunkSource().removeRegionTicket(UMCTICKET, chunkpos, KEEP_RADIUS, chunkpos);
                it.remove();
                dropped++;
            } else {
                server.getChunkSource().addRegionTicket(UMCTICKET, chunkpos, KEEP_RADIUS, chunkpos);
                kept++;
            }
        }
        if (dropped > 0 || now % 200 == 0) {
            ModCore.debug("[UMCDBG] keeping %s chunks (%s dropped this tick)", kept, dropped);
        }
    }

    static void flagEntityPos(World world, Vec3i inPos) {
        if (world.isClient) {
            return;
        }

        ChunkPos chunkpos = new ChunkPos(inPos.internal());
        ServerLevel server = (ServerLevel) world.internal;
        Map<Long, Long> keeping = remembered(world);

        /*
         * Neighbouring cars (and a train crawling forward) ask for adjacent chunks
         * every tick.  Reuse an anchor that already covers this chunk instead of
         * adding one entry per block travelled, otherwise a long trip would end up
         * force loading the entire route.
         */
        long key = chunkpos.toLong();
        boolean covered = false;
        for (int dx = -KEEP_RADIUS; !covered && dx <= KEEP_RADIUS; dx++) {
            for (int dz = -KEEP_RADIUS; dz <= KEEP_RADIUS; dz++) {
                long near = ChunkPos.asLong(chunkpos.x + dx, chunkpos.z + dz);
                if (keeping.containsKey(near)) {
                    key = near;
                    covered = true;
                    break;
                }
            }
        }
        keeping.put(key, world.getTicks());

        server.getChunkSource().addRegionTicket(UMCTICKET, chunkpos, KEEP_RADIUS, chunkpos);
        loadNow(server, chunkpos);
    }

    private static Path chunkData(ServerLevel world) {
        File dir = new File(world.getServer().storageSource.getDimensionPath(world.dimension()).toString(), "umc");
        dir.mkdirs();
        return new File(dir, "chunks.raw").toPath();
    }

    public static void setup() {
        // Forces UMCTICKET registration
        CommonEvents.World.SAVE.subscribe(world -> {
            Set<Long> currentlyForced = new TreeSet<>();
            Map<Long, Long> keeping = null;
            synchronized (REMEMBERED) {
                keeping = REMEMBERED.get(World.get(world));
            }
            if (keeping != null) {
                currentlyForced.addAll(keeping.keySet());
            }
            world.getChunkSource().distanceManager.tickets.forEach((pos, tickets) -> {
                for (Ticket<?> ticket : tickets) {
                    if (ticket.getType() == UMCTICKET) {
                        currentlyForced.add(pos);
                    }
                }
            });
            List<String> lines = new ArrayList<>();
            for (long pos : currentlyForced) {
                lines.add(Long.toString(pos));
            }
            try {
                Files.write(chunkData(world), lines, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                ModCore.debug("[UMCDBG] saved %s remembered chunks", lines.size());
            } catch (IOException e) {
                ModCore.catching(e);
            }
        });
        CommonEvents.World.LOAD.subscribe(world -> {
            if (!world.isClientSide) {
                try {
                    ServerLevel server = (ServerLevel) world;
                    Path dataFile = chunkData(server);
                    if (dataFile.toFile().exists()) {
                        List<ChunkPos> pending = new ArrayList<>();
                        Map<Long, Long> keeping = remembered(World.get(world));
                        keeping.clear();
                        long now = server.getGameTime();
                        for (String line : Files.readAllLines(dataFile)) {
                            try {
                                ChunkPos chunkpos = new ChunkPos(Long.parseLong(line.trim()));
                                keeping.put(chunkpos.toLong(), now);
                                server.getChunkSource().addRegionTicket(UMCTICKET, chunkpos, KEEP_RADIUS, chunkpos, true);
                                pending.add(chunkpos);
                            } catch (RuntimeException e) {
                                ModCore.catching(e);
                            }
                        }
                        synchronized (PENDING_LOADS) {
                            PENDING_LOADS.put(World.get(world), pending);
                        }
                        ModCore.debug("[UMCDBG] chunks.raw has %s entries", pending.size());
                    }
                } catch (IOException e) {
                    ModCore.catching(e);
                }
            }
        });
    }
}
