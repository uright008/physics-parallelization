# 物理并行化重新实现规划

## 一、入口点 & 需要多线程化的方法

### 调用链

```
ServerLevel.tick()                              [line ~590]
  → entityTickList.forEach(consumer)            → 这是要拦截的入口
    → consumer(lambda$tick$0)                    → 每个实体调用一次
      → tickNonPassenger(entity)
        → entity.tick()                          ★ 主要并行化目标
          ├─ entity.baseTick()                   — 火焰/冰冻/传送门/流体交互等
          ├─ checkInsideBlocks()                 — 方块碰撞检测和推出
          ├─ move(MoverType, Vec3)               — 最终移动（含碰撞解决）
          ├─ applyEffectsFromBlocks()            — 方块效果（灵魂沙、岩浆块等）
          ├─ LivingEntity.pushEntities()         — 实体间碰撞（读/写其他实体）
          ├─ Mob.serverAiStep()                  ★ 最大的 CPU 消耗
          │   ├─ goalSelector.tick()             — AI 目标选择
          │   ├─ targetSelector.tick()           — 攻击目标选择
          │   └─ navigation.tick()               — 寻路更新
          ├─ Mob.aiStep()                        — 视线控制、跳跃控制
          └─ tickPassenger()                     — 递归 tick 骑乘者
```

### 并行策略

- **Mob 实体**：分 batch 提交到 `CompletableFuture` 并行 tick
- **非 Mob 实体**（ItemEntity, Arrow, etc）：在主线程顺序 tick（计算量小，且与世界交互复杂）
- **阈值**：Mob 数量 < minParallelEntities（默认4）→ 回退到原版

### 需要防止死锁的地方

| 死锁来源 | 原因 | 解决方案 |
|---------|------|---------|
| `ServerChunkCache.getChunk()` | 内部通过 `CompletableFuture.get()` 调度到主线程；主线程正在 join worker → 死锁 | 工作线程使用 `ChunkSafeAccessor.parallelCore$getChunkSafe(x, z)` 直接读取已加载 chunk |
| `Level.getBlockEntity()` | 检测到非主线程 → 返回 null，逻辑错误 | `SafeLevelAccess` + `LevelSafeAccessMixin` 绕过 `Thread.currentThread()` 检查 |
| 主线程 join worker，worker 需要主线程 | 工作线程内的任何操作触发了主线程 dispatch | 必须在安全区内完成所有依赖主线程的操作 |

---

## 二、与世界交互的数据结构

### 读取操作（并行阶段）

| 调用 | 数据源 | 当前线程安全性 |
|------|--------|---------------|
| `level.getBlockState(pos)` | `LevelChunk.palettedContainer` | ❌ 通过 `Level.getChunk()`，非主线程走 CompFuture 调度 |
| `level.getFluidState(pos)` | `LevelChunk.palettedContainer` | ❌ 同上 |
| `level.getEntities(aabb, predicate)` | `EntitySectionStorage` | ❌ 非线程安全 + 与并行 tick 并发修改 |
| `level.getRandom()` | `LegacyRandomSource` | ❌ CAS 失败直接抛异常 |
| `level.getBlockEntity(pos)` | `LevelChunk.blockEntities` | ❌ `Thread.currentThread() != this.thread → null` |
| `entity.getBoundingBox()` | Entity 字段 | ✓ 单 worker tick 单一实体，安全 |
| `entity.chunkPosition()` | Entity 字段 | ✓ |
| `entity.isRemoved()` | volatile boolean | ✓ |
| `EntitySelector` 过滤 | 无状态谓词 | ✓ |

### 写入操作（需要特殊处理）

| 操作 | 修改目标 | 冲突场景 |
|------|---------|---------|
| `entity.setPos()` | `EntitySectionStorage.sections`, `.sectionIds` | Worker A 移动实体 X，Worker B 查询 → CME |
| `entity.move()` | `EntitySectionStorage` + 实体 AABB | 同上 |
| `entity.hurtServer()` | Entity 生命 + `ChunkMap` | 多 worker 同时 → 陈腐数据 |
| `entity.push(other)` | 其他实体的 `deltaMovement` | 跨 worker 数据竞争 |
| `ChunkMap.addEntity()/removeEntity()` | `ChunkMap.entityMap` | 并发 add → fastutil map 损坏 |
| `navigatingMobs.add/remove` | `ServerLevel.navigatingMobs` | 并发 add + 遍历 → NPE |
| `ClassInstanceMultiMap.add/remove` | HashMap + ArrayList (EntitySection 内部) | 并发 computeIfAbsent → 损坏 |
| `level.playSound()`/broadcastEntityEvent | 玩家网络连接 | ✓（网络包入队是线程安全的） |
| `level.explode()` | 新爆炸 + 方块修改 | ❌ 必须排队到主线程 |

---

## 三、如何提供脱离主线程的只读访问

### 3.1 方块状态/流体状态 → ChunkSafeAccessor

已有 `ServerChunkCacheMixin`，工作线程可直接调用：

```java
ChunkSafeAccessor scs = (ChunkSafeAccessor) level.getChunkSource();
ChunkAccess chunk = scs.parallelCore$getChunkSafe(cx, cz);
BlockState state = chunk.getBlockState(pos);
FluidState fluid = chunk.getFluidState(pos);
```

**前提**：并行 tick 前必须在主线程预加载所有涉及到的 chunk：
```java
for (Entity e : entities) {
    int cx = SectionPos.blockToSectionCoord(e.getBlockX());
    int cz = SectionPos.blockToSectionCoord(e.getBlockZ());
    level.getChunk(cx, cz);  // 主线程正常路径，无阻塞
}
```

### 3.2 实体查询 → 快照网格

并行 tick 前在**主线程**构建只读快照：

```java
EntityGrid grid = new EntityGrid(entityTickList);
// 按 SectionPos.asLong(x, y, z) 分桶: HashMap<Long, List<Entity>>
```

工作线程通过 `EntityGridManager` 获取：
```java
EntityGrid grid = EntityGridManager.activeGrid();
grid.getEntities(except, aabb, predicate);  // O(1) section 查找，只读
```

同时 `EntityQueryMixin` 注入所有 `level.getEntities()` 调用 → 自动重定向到快照。

### 3.3 Random → ThreadSafeRandomSource

使用与 vanilla 相同的 LCG 算法，CAS retry 代替抛异常。

`LevelRandomMixin` 在 Level 构造时替换 `this.random`。

### 3.4 BlockEntity → SafeLevelAccess + LevelSafeAccessMixin

`LevelSafeAccessMixin` 在安全区内：
- `getBlockEntity()`: 将 `Thread.currentThread()` 替换为 `this.thread`
- `getChunk(int,int)`: 用 `ChunkSafeAccessor` 提供 chunk，不触发主线程调度
- `getChunkForCollisions(int,int)`: 同上

---

## 四、并行计算后如何写回

### 4.1 实体自身状态 → 直接写入

每个 entity 只被一个 worker tick，内部字段无冲突：

```java
entity.setPos(x, y, z);           // 内部位置字段
entity.setDeltaMovement(vx, vy, vz); // 速度
entity.setOnGround(true);          // 地面状态
entity.setRemainingFireTicks(n);   // 火焰 tick
```

### 4.2 EntitySectionStorage → 同步 Mixin

`entity.setPos()` 内部触发 `EntitySectionStorage` 修改。用同步解决：

| Mixin | 方法 | 策略 |
|-------|------|------|
| `EntitySectionStorageMixin` | `getOrCreateSection` | `synchronized(this) { ... }` |
| `EntitySectionStorageMixin` | `remove` | `synchronized(this) { ... }` |
| `ClassInstanceMultiMapMixin` | `find` | `synchronized` + return `new ArrayList<>(instances)` |
| `ClassInstanceMultiMapMixin` | `add` / `remove` | `synchronized` |
| `ClassInstanceMultiMapMixin` | `iterator` | return `new ArrayList<>(this.allInstances).iterator()` |

### 4.3 ChunkMap → 同步 Mixin

| Mixin | 方法 | 策略 |
|-------|------|------|
| `ChunkMapMixin` | `addEntity` | `synchronized(entityMap) { invokeAddEntity(entity); }` |
| `ChunkMapMixin` | `removeEntity` | `synchronized(entityMap) { invokeRemoveEntity(entity); }` |

### 4.4 NavigatingMobs → 同步 Mixin

| Mixin | 方法 | 策略 |
|-------|------|------|
| `NavigatingMobsMixin` | `onTrackingStart` → `set.add()` | `synchronized(set) { set.add(mob); }` |
| `NavigatingMobsMixin` | `onTrackingEnd` → `set.remove()` | `synchronized(set) { set.remove(mob); }` |
| `ServerLevelSafeIterationMixin` | `sendBlockUpdated` → `set.iterator()` | `synchronized(set) { new ArrayList<>(set).iterator(); }` |

### 4.5 跨实体交互 → 排队到主线程

`push()`/`knockback()` 修改其他实体的速度，排队执行：

```java
// 工作线程中
pushQueue.add(new PushEvent(pusher, pushed, force));

// 主线程写回阶段
for (PushEvent e : pushQueue) {
    if (!e.pushed.isRemoved()) e.pushed.push(e.force);
}
```

### 4.6 生命事件 → 排队到主线程

```java
// 工作线程中
damageQueue.add(new DamageEvent(entity, damageSource, damage));
despawnQueue.add(entity);

// 主线程写回阶段
for (DamageEvent e : damageQueue) {
    if (!e.entity.isRemoved()) {
        e.entity.hurtServer(level, e.source, e.amount);
    }
}
for (Entity e : despawnQueue) {
    if (!e.isRemoved()) e.setRemoved(RemovalReason.DISCARDED);
}
```

### 4.7 爆炸 → 排队到主线程

实体 tick 可能触发爆炸（Creeper 等），不能在工作线程创建爆炸：

```java
// 工作线程中
explosionQueue.add(new ExplosionEvent(center, radius, ...));

// 主线程写回阶段
for (ExplosionEvent e : explosionQueue) {
    level.explode(...);
}
```

---

## 五、完整执行流程

```
═══════ 主线程：准备阶段 ═══════
1. 收集所有 tickable 实体，拆分 Mob vs 非 Mob
2. 如果 mobs.size() < minParallelEntities → 回退原版
3. 预加载所有实体涉及到的 chunk
4. 构建 EntityGrid 快照
5. EntityGridManager.setActiveGrid(grid)
6. SafeLevelAccess.enterSafeZone()
7. 创建队列: damageQueue, despawnQueue, pushQueue, explosionQueue

═══════ 并行阶段 ═══════
8. 主线程: 顺序 tick 所有非 Mob 实体
9. Mob 实体按 batch 分发到 CompletableFuture:
   - Worker 线程进入/退出安全区
   - entity.tick() (内部直接写自身字段 + 同步写 EntitySectionStorage)
   - 跨实体/世界修改 → 入队到并发队列
10. 主线程参与最后一个 batch
11. CompletableFuture.allOf(futures).join()

═══════ 主线程：写回阶段 ═══════
12. 执行 explosionQueue
13. 执行 pushQueue (跨实体推挤)
14. 执行 damageQueue (伤害/死亡)
15. 执行 despawnQueue (despawn)

═══════ 主线程：清理阶段 ═══════
16. EntityGridManager.setActiveGrid(null)
17. SafeLevelAccess.leaveSafeZone()
```

## 六、需要补充/还原的文件

### 新建
- `physics-parallelization/.../mixin/ServerLevelMixin.java` — 核心 Mixin

### 还原到 parallel-core
- `EntityGrid.java` — 实体空间索引快照
- `EntityGridManager.java` — 快照 holder
- `ThreadSafeRandomSource.java` — 线程安全随机数

### 还原 Mixin 到 parallel-core
- `EntitySectionStorageMixin.java`
- `ClassInstanceMultiMapMixin.java`
- `ChunkMapMixin.java`
- `NavigatingMobsMixin.java`
- `ServerLevelSafeIterationMixin.java`
- `LevelRandomMixin.java`
- `EntityQueryMixin.java`
