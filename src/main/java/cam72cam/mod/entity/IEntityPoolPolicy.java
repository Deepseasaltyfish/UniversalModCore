package cam72cam.mod.entity;

/**
 * 实体池策略，供具体 Mod 实现（如 IR）
 */
public interface IEntityPoolPolicy {
    /** 池子是否启用（对应 IR 的 Config） */
    boolean isEnabled();

    /** 这个实体是否应该被纳入池子管理 */
    boolean shouldPool(CustomEntity self);

    /** 进池子的距离平方（如 128*128） */
    double getSpawnDistanceSq();

    /** 出池子的距离平方（如 160*160） */
    double getDespawnDistanceSq();

    /** 扫描间隔（tick） */
    int getProximityInterval();
}