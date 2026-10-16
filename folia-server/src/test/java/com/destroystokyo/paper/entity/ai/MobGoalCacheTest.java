package com.destroystokyo.paper.entity.ai;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowMobGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import org.bukkit.entity.Mob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobGoalCacheTest {
    private static final List<Class<? extends Goal>> GOALS = List.of(
        FloatGoal.class, FollowMobGoal.class, LookAtPlayerGoal.class, MeleeAttackGoal.class,
        PanicGoal.class, RandomLookAroundGoal.class, RandomStrollGoal.class, WaterAvoidingRandomStrollGoal.class
    );

    @Test
    void concurrentColdLookupsPreserveEveryGoalKey() throws Exception {
        Field field = MobGoalHelper.class.getDeclaredField("GENERIC_TYPE_CACHE");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Class<? extends Goal>, Class<? extends Mob>> cache =
            (Map<Class<? extends Goal>, Class<? extends Mob>>) field.get(null);
        Map<Class<? extends Goal>, GoalKey<?>> expected = new LinkedHashMap<>();
        for (Class<? extends Goal> goal : GOALS) {
            expected.put(goal, MobGoalHelper.getKey(goal));
        }
        try (var workers = Executors.newFixedThreadPool(GOALS.size())) {
            for (int round = 0; round < 64; round++) {
                GOALS.forEach(cache::remove);
                CountDownLatch ready = new CountDownLatch(GOALS.size());
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> results = new ArrayList<>();
                for (int index = 0; index < GOALS.size(); index++) {
                    int first = index;
                    results.add(workers.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        for (int offset = 0; offset < GOALS.size(); offset++) {
                            Class<? extends Goal> goal = GOALS.get((first + offset) % GOALS.size());
                            assertEquals(expected.get(goal), MobGoalHelper.getKey(goal));
                        }
                        return null;
                    }));
                }
                try {
                    assertTrue(ready.await(10, TimeUnit.SECONDS));
                } finally {
                    start.countDown();
                }
                for (Future<?> result : results) {
                    result.get(10, TimeUnit.SECONDS);
                }
                for (Class<? extends Goal> goal : GOALS) {
                    assertSame(expected.get(goal).getEntityClass(), cache.get(goal));
                }
            }
        }
    }
}
