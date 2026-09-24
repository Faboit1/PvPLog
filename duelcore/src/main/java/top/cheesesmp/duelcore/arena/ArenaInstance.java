package top.cheesesmp.duelcore.arena;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

/** A pasted copy of a template in one slot of the arena world. */
public final class ArenaInstance {

    public enum State { PASTING, READY, IN_USE, RESETTING, CLEARING, DEAD }

    private final int id;
    private final ArenaTemplate template;
    private final int slot;
    private final World world;
    private final int ox;
    private final int oy;
    private final int oz;
    private final BoundingBox bounds;
    /** Blocks placed during the current match (packed positions), for break-mode "placed". */
    private final Set<Long> placed = new HashSet<>();
    private volatile State state = State.PASTING;
    private int uses;

    public ArenaInstance(int id, ArenaTemplate template, int slot, World world, int ox, int oy, int oz) {
        this.id = id;
        this.template = template;
        this.slot = slot;
        this.world = world;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.bounds = new BoundingBox(ox, oy, oz, ox + template.sizeX(), oy + template.sizeY(), oz + template.sizeZ());
    }

    public int id() {
        return id;
    }

    public ArenaTemplate template() {
        return template;
    }

    public int slot() {
        return slot;
    }

    public World world() {
        return world;
    }

    public int originX() {
        return ox;
    }

    public int originY() {
        return oy;
    }

    public int originZ() {
        return oz;
    }

    public BoundingBox bounds() {
        return bounds;
    }

    public State state() {
        return state;
    }

    void state(State state) {
        this.state = state;
    }

    public int uses() {
        return uses;
    }

    void markUsed() {
        uses++;
    }

    public Location spawn(int index) {
        RelPos p = index == 0 ? template.spawn1() : template.spawn2();
        return new Location(world, ox + p.x(), oy + p.y(), oz + p.z(), p.yaw(), p.pitch());
    }

    public Location center() {
        return new Location(world, ox + template.sizeX() / 2.0, oy + Math.min(template.spawn1().y(), template.spawn2().y()),
            oz + template.sizeZ() / 2.0);
    }

    /** Highest Y (exclusive) at which players may place blocks. */
    public int buildLimitY() {
        double lowestSpawn = Math.min(template.spawn1().y(), template.spawn2().y());
        return (int) Math.min(oy + template.sizeY(), oy + lowestSpawn + template.buildHeight());
    }

    public boolean contains(Location loc) {
        return loc.getWorld() == world && bounds.contains(loc.getX(), loc.getY(), loc.getZ());
    }

    public boolean containsBlock(int x, int y, int z) {
        return x >= ox && x < ox + template.sizeX() && y >= oy && y < oy + template.sizeY()
            && z >= oz && z < oz + template.sizeZ();
    }

    /** Horizontal containment with a margin (players may stand on the edge). */
    public boolean containsXZ(double x, double z, double margin) {
        return x >= ox - margin && x <= ox + template.sizeX() + margin && z >= oz - margin && z <= oz + template.sizeZ() + margin;
    }

    public double floorY() {
        return oy;
    }

    public void markPlaced(int x, int y, int z) {
        placed.add(pack(x, y, z));
    }

    public boolean wasPlaced(int x, int y, int z) {
        return placed.contains(pack(x, y, z));
    }

    public void forgetPlaced(int x, int y, int z) {
        placed.remove(pack(x, y, z));
    }

    public void clearPlaced() {
        placed.clear();
    }

    public int placedCount() {
        return placed.size();
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
