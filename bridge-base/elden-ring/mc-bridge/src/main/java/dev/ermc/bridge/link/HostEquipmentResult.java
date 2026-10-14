package dev.ermc.bridge.link;

/** Host-confirmed outcome; unknown results must never be automatically retried. */
public record HostEquipmentResult(int status, int equipmentSlot, int handle, int itemId,
        int inventorySlot, int hostLife, long nonce, long snapshotToken, long sampledAt, long hostStartMs) {
    public boolean successful() { return status == 0; }
    public boolean unknown() { return status == -50; }
    public String message() {
        return switch (status) {
            case 0 -> "法环已确认装备变更";
            case -41 -> "当前法环版本尚未验证装备操作";
            case -42 -> "请在 MC 接管的安全地面操作";
            case -43, -44 -> "角色或场景已变化，请刷新装备页";
            case -45, -49 -> "请求已失效，请刷新后再操作";
            case -46 -> "法环库存已变化，请刷新后重新选择";
            case -47 -> "物品不在当前背包或不适合此装备槽";
            case -48 -> "首版仅支持四个法环防具槽";
            case -50 -> "操作结果暂未确认；请核对法环装备，不会自动重试";
            case -51 -> "操作过于频繁，请稍候";
            default -> "装备请求无效";
        };
    }
}
