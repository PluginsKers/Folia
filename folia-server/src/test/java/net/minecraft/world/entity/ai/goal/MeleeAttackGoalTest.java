package net.minecraft.world.entity.ai.goal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.sensing.Sensing;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.Path;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
class MeleeAttackGoalTest {
    private static final class Fixture {
        final Zombie zombie = mock(Zombie.class);
        final Player player = mock(Player.class);
        final ServerLevel level = mock(ServerLevel.class);
        final PathNavigation navigation = mock(PathNavigation.class);
        final ZombieAttackGoal goal = new ZombieAttackGoal(zombie, 1.0, false);

        Fixture() {
            when(zombie.level()).thenReturn(level);
            when(level.getGameTime()).thenAnswer(call -> (long) zombie.tickCount);
            when(zombie.getTarget()).thenReturn(player);
            when(player.isAlive()).thenReturn(true);
            when(zombie.getNavigation()).thenReturn(navigation);
            when(navigation.createPath(player, 0)).thenReturn(mock(Path.class));
            when(zombie.getLookControl()).thenReturn(mock(LookControl.class));
            when(zombie.getRandom()).thenReturn(RandomSource.create(0));
            Sensing sensing = mock(Sensing.class);
            when(zombie.getSensing()).thenReturn(sensing);
            when(sensing.hasLineOfSight(player)).thenReturn(true);
        }

        void approachAndAttack() {
            zombie.tickCount = 100;
            assertTrue(goal.canUse());
            goal.start();
            for (int tick = 100; tick < 125; ++tick) tick(tick);
            when(zombie.isWithinMeleeAttackRange(player)).thenReturn(true);
            tick(125);
            verify(zombie).doHurtTarget(level, player);
        }

        void finishNavigation() {
            zombie.tickCount = 126;
            when(navigation.isDone()).thenReturn(true);
            assertFalse(goal.canContinueToUse());
            goal.stop();
        }

        void restart(int tick) {
            zombie.tickCount = tick;
            assertTrue(goal.canUse());
            goal.start();
            when(navigation.isDone()).thenReturn(false);
            tick(tick);
        }

        void tick(int tick) {
            zombie.tickCount = tick;
            goal.tick();
        }
    }

    @Test
    void finishedPathCannotRestartAnAttackOnTheNextTick() {
        Fixture f = new Fixture();
        f.approachAndAttack();
        f.finishNavigation();
        f.restart(126);
        verify(f.zombie, times(1)).doHurtTarget(f.level, f.player);
        for (int tick = 127; tick < 145; ++tick) f.tick(tick);
        verify(f.zombie, times(1)).doHurtTarget(f.level, f.player);
        f.tick(145);
        verify(f.zombie, times(2)).doHurtTarget(f.level, f.player);
    }

    @Test
    void stoppedTimeCountsTowardsTheExistingCooldown() {
        Fixture f = new Fixture();
        f.approachAndAttack();
        f.finishNavigation();
        f.restart(135);
        verify(f.zombie, times(1)).doHurtTarget(f.level, f.player);
        for (int tick = 136; tick <= 145; ++tick) f.tick(tick);
        verify(f.zombie, times(2)).doHurtTarget(f.level, f.player);
    }

    @Test
    void expiredCooldownAllowsAnImmediateAttackAfterRestart() {
        Fixture f = new Fixture();
        f.approachAndAttack();
        f.finishNavigation();
        f.restart(160);
        verify(f.zombie, times(2)).doHurtTarget(f.level, f.player);
    }

    @Test
    void restartPreservesACustomRemainingCooldown() throws Exception {
        Fixture f = new Fixture();
        f.approachAndAttack();
        // Conflux's drowned goal writes this field to retain vanilla attacks at half speed.
        var cooldown = MeleeAttackGoal.class.getDeclaredField("ticksUntilNextAttack");
        cooldown.setAccessible(true);
        cooldown.setInt(f.goal, 40);
        f.finishNavigation();
        f.restart(126);
        for (int tick = 127; tick < 165; ++tick) f.tick(tick);
        verify(f.zombie, times(1)).doHurtTarget(f.level, f.player);
        f.tick(165);
        verify(f.zombie, times(2)).doHurtTarget(f.level, f.player);
    }
}
