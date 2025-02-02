package net.glowstone.entity;

import com.destroystokyo.paper.block.TargetBlockInfo;
import com.destroystokyo.paper.entity.TargetEntityInfo;
import com.flowpowered.network.Message;
import lombok.Getter;
import lombok.Setter;
import net.glowstone.EventFactory;
import net.glowstone.block.GlowBlock;
import net.glowstone.block.ItemTable;
import net.glowstone.block.blocktype.BlockType;
import net.glowstone.constants.GameRules;
import net.glowstone.constants.GlowPotionEffect;
import net.glowstone.entity.AttributeManager.Key;
import net.glowstone.entity.ai.MobState;
import net.glowstone.entity.ai.TaskManager;
import net.glowstone.entity.meta.MetadataIndex;
import net.glowstone.entity.monster.GlowSlime;
import net.glowstone.entity.objects.GlowExperienceOrb;
import net.glowstone.entity.objects.GlowLeashHitch;
import net.glowstone.entity.passive.GlowWolf;
import net.glowstone.entity.projectile.GlowProjectile;
import net.glowstone.inventory.EquipmentMonitor;
import net.glowstone.net.GlowSession;
import net.glowstone.net.message.play.entity.CollectItemMessage;
import net.glowstone.net.message.play.entity.EntityAnimationMessage;
import net.glowstone.net.message.play.entity.EntityEffectMessage;
import net.glowstone.net.message.play.entity.EntityEquipmentMessage;
import net.glowstone.net.message.play.entity.EntityHeadRotationMessage;
import net.glowstone.net.message.play.entity.EntityRemoveEffectMessage;
import net.glowstone.net.message.play.player.InteractEntityMessage;
import net.glowstone.net.message.play.player.InteractEntityMessage.Action;
import net.glowstone.util.ExperienceSplitter;
import net.glowstone.util.InventoryUtil;
import net.glowstone.util.Position;
import net.glowstone.util.RayUtil;
import net.glowstone.util.SoundUtil;
import net.glowstone.util.loot.LootData;
import net.glowstone.util.loot.LootingManager;
import org.bukkit.Color;
import org.bukkit.EntityAnimation;
import org.bukkit.EntityEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityCategory;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Criterias;
import org.bukkit.scoreboard.Objective;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static java.lang.Math.cos;
import static java.lang.Math.sin;

/**
 * A GlowLivingEntity is a {@link Player} or {@link Monster}.
 *
 * @author Graham Edgecombe.
 */
public abstract class GlowLivingEntity extends GlowEntity implements LivingEntity {

    /**
     * The entity's AI task manager.
     */
    @Getter
    protected final TaskManager taskManager;
    /**
     * Potion effects on the entity.
     */
    private final Map<PotionEffectType, PotionEffect> potionEffects = new ConcurrentHashMap<>();
    /**
     * The LivingEntity's AttributeManager.
     */
    @Getter
    private final AttributeManager attributeManager;
    /**
     * The entity's health.
     */
    @Getter
    protected double health;
    /**
     * The entity's max health.
     */
    protected double maxHealth;
    /**
     * The LivingEntity's number of ticks since death.
     */
    @Getter
    protected int deathTicks;
    /**
     * <p>The entity's movement as a unit vector, applied each tick according to the entity's speed.
     * </p><p>
     * The y value is not used. X is used for forward movement and z is used for sideways movement.
     * These values are relative to the entity's current yaw.</p>
     */
    @Getter
    @Setter
    protected Vector movement = new Vector();
    /**
     * The speed multiplier of the entity.
     */
    @Getter
    @Setter
    protected double speed = 1;
    /**
     * The player that killed this entity, or null if not killed by a player.
     */
    @Getter
    @Setter
    private Player killer;
    /**
     * The tick that the entity got hit by a player.
     * The default value was set to -101 rather than 0.
     */
    @Getter
    @Setter
    private int playerDamageTick = -101;
    /**
     * Whether entities can collide with this entity.
     */
    @Getter
    @Setter
    private boolean collidable = true;
    /**
     * The number of arrows stuck inside this entity.
     */
    @Getter
    @Setter
    private int arrowsStuck = 0;
    /**
     * The number of arrows in an entity's body.
     * <br>
     * TODO: Investigate difference between this and `arrowsStuck`.
     */
    @Getter
    @Setter
    private int arrowsInBody = 0;
    /**
     * The time in ticks until the next arrow leaves the entity's body.
     */
    @Getter
    @Setter
    private int arrowCooldown = 0;
    /**
     * The entity's absorption amount (the amount of health supplementing its max health).
     */
    @Getter
    @Setter
    private double absorptionAmount = 0;
    /**
     * Whether the entity is invisible.
     */
    @Getter
    @Setter
    private boolean invisible = false;
    /**
     * The entity's hurt direction (angle).
     */
    @Getter
    @Setter
    private float hurtDirection;
    /**
     * The magnitude of the last damage the entity took.
     */
    @Getter
    @Setter
    private double lastDamage;
    /**
     * How long the entity has until it runs out of air.
     */
    @Getter
    private int remainingAir = 300;
    /**
     * The maximum amount of air the entity can hold.
     */
    @Getter
    private int maximumAir = 300;
    /**
     * The number of ticks remaining in the invincibility period.
     */
    @Getter
    @Setter
    private int noDamageTicks;
    /**
     * The default length of the invincibility period.
     */
    @Getter
    @Setter
    private int maximumNoDamageTicks = 10;
    /**
     * Whether the entity should be removed if it is too distant from players.
     */
    @Setter
    private boolean removeWhenFarAway;
    /**
     * Whether the (non-Player) entity can pick up armor and tools.
     */
    @Setter
    private boolean canPickupItems;
    /**
     * Monitor for the equipment of this entity.
     */
    @Getter
    private EquipmentMonitor equipmentMonitor = new EquipmentMonitor(this);
    /**
     * Whether the entity can automatically glide when falling with an Elytra equipped. This value
     * is ignored for players.
     */
    @Getter
    @Setter
    private boolean fallFlying;
    /**
     * Ticks until the next ambient sound roll.
     */
    private int nextAmbientTime = 1;
    /**
     * The last entity which damaged this living entity.
     */
    @Getter
    @Setter
    private Entity lastDamager;
    /**
     * The head rotation of the living entity, if applicable.
     */
    @Getter
    private float headYaw;
    /**
     * Whether the headYaw value should be updated.
     */
    private boolean headRotated;
    /**
     * The entity's current AI state.
     */
    @Getter
    private MobState state = MobState.NO_AI;
    /**
     * If this entity has swam in lava (for fire application).
     */
    private boolean swamInLava;
    /**
     * If this entity has stood in fire (for fire application).
     */
    private boolean stoodInFire;
    /**
     * The ticks an entity stands adjacent to fire and lava.
     */
    private int adjacentBurnTicks;
    /**
     * If potion effects of entity changed
     */
    private boolean potionEffectsChanged;
    /**
     * If the entity is jumping.
     */
    private boolean jumping;

    /**
     * Creates a mob within the specified world.
     *
     * @param location The location.
     */
    public GlowLivingEntity(Location location) {
        this(location, 20);
    }

    /**
     * Creates a mob within the specified world.
     *
     * @param location  The location.
     * @param maxHealth The max health of this mob.
     */
    protected GlowLivingEntity(Location location, double maxHealth) {
        super(location);
        attributeManager = new AttributeManager(this);
        this.maxHealth = maxHealth;
        attributeManager.setProperty(Key.KEY_MAX_HEALTH, maxHealth);
        health = maxHealth;
        taskManager = new TaskManager(this);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Internals

    @Override
    public void pulse() {
        super.pulse();

        if (isDead()) {
            deathTicks++;
            if (deathTicks >= 20 && getClass() != GlowPlayer.class) {
                remove();
            }
        }

        // invulnerability
        if (noDamageTicks > 0) {
            --noDamageTicks;
        }

        Material mat = getEyeLocation().getBlock().getType();
        // breathing
        if (mat == Material.WATER) { // todo: flowing_water?
            if (canTakeDamage(DamageCause.DROWNING)) {
                --remainingAir;
                if (remainingAir <= -20) {
                    remainingAir = 0;
                    damage(1, DamageCause.DROWNING);
                }
            }
        } else {
            remainingAir = maximumAir;
        }

        if (isTouchingMaterial(Material.CACTUS)) {
            damage(1, DamageCause.CONTACT);
        }
        if (location.getY() < -64) { // no canTakeDamage call - pierces through game modes
            damage(4, DamageCause.VOID);
        }

        if (isWithinSolidBlock()) {
            damage(1, DamageCause.SUFFOCATION);
        }

        // fire and lava damage
        if (getLocation().getBlock().getType() == Material.FIRE) {
            damage(1, DamageCause.FIRE);
            // not applying additional fire ticks after dying in fire
            stoodInFire = !isDead();
        } else if (getLocation().getBlock().getType() == Material.LAVA) {
            damage(4, DamageCause.LAVA);
            if (swamInLava) {
                setFireTicks(getFireTicks() + 2);
            } else {
                setFireTicks(getFireTicks() + 300);
                swamInLava = true;
            }
        } else if (isTouchingMaterial(Material.FIRE)
                || isTouchingMaterial(Material.LAVA)) {
            damage(1, DamageCause.FIRE);
            // increment the ticks stood adjacent to fire or lava
            adjacentBurnTicks++;
            if (adjacentBurnTicks > 40) {
                stoodInFire = !isDead();
            }
        } else if (stoodInFire) {
            setFireTicks(getFireTicks() + 160);
            stoodInFire = false;
            adjacentBurnTicks = 0;
        } else {
            swamInLava = false;
            if (getLocation().getBlock().getType() == Material.WATER) {
                setFireTicks(0);
            }
        }

        // potion effects
        List<PotionEffect> effects = new ArrayList<>(potionEffects.values());
        for (PotionEffect effect : effects) {
            // pulse effect
            PotionEffectType type = effect.getType();
            GlowPotionEffect glowType = GlowPotionEffect.getEffect(type);
            if (glowType != null) {
                glowType.pulse(this, effect);
            }

            if (effect.getDuration() > 0) {
                // reduce duration on server-side. Don't need to be updated to client
                potionEffects.put(type, effect.withDuration(effect.getDuration() - 1));
            } else {
                // remove
                removePotionEffect(type);
            }
        }

        if (potionEffectsChanged) {
            updatePotionEffectsMetadata();
            potionEffectsChanged = false;
        }

        if (getFireTicks() > 0 && getFireTicks() % 20 == 0) {
            damage(1, DamageCause.FIRE_TICK);
        }

        GlowBlock under = (GlowBlock) getLocation().getBlock().getRelative(BlockFace.DOWN);
        BlockType type = ItemTable.instance().getBlock(under.getType());
        if (type != null) {
            type.onEntityStep(under, this);
        }
        nextAmbientTime--;
        if (!isDead() && getAmbientSound() != null && nextAmbientTime == 0 && !isSilent()) {
            double v = ThreadLocalRandom.current().nextDouble();
            if (v <= 0.2) {
                world
                        .playSound(getLocation(), getAmbientSound(), getSoundVolume(),
                                getSoundPitch());
            }
        }
        if (nextAmbientTime == 0) {
            nextAmbientTime = getAmbientDelay();
        }
    }

    @Override
    protected void pulsePhysics() {
        // drag application
        movement.multiply(airDrag);
        // convert movement x/z to a velocity
        Vector velMovement = getVelocityFromMovement();
        velocity.add(velMovement);
        if (jumping) {
            jump();
        }
        super.pulsePhysics();
    }

    protected Vector getVelocityFromMovement() {
        // ensure movement vector is in correct format
        movement.setY(0);

        double mag = movement.getX() * movement.getX() + movement.getZ() * movement.getZ();
        // don't do insignificant movement
        if (mag < 0.01) {
            return new Vector();
        }
        // unit vector of movement
        movement.setX(movement.getX() / mag);
        movement.setZ(movement.getZ() / mag);

        // scale to how fast the entity can go
        mag *= speed;
        Vector movement = this.movement.clone();
        movement.multiply(mag);

        // make velocity vector relative to where the entity is facing
        double yaw = Math.toRadians(location.getYaw());
        double z = Math.sin(yaw);
        double x = Math.cos(yaw);
        movement.setX(movement.getZ() * x - movement.getX() * z);
        movement.setZ(movement.getX() * x + movement.getZ() * z);

        // apply the movement multiplier
        if (!isOnGround() || location.getBlock().isLiquid()) {
            // constant multiplier in liquid or not on ground
            movement.multiply(0.02);
        } else {
            this.slipMultiplier = ((GlowBlock) location.getBlock()).getMaterialValues()
                    .getSlipperiness();
            double slipperiness = slipMultiplier * 0.91;
            movement.multiply(0.1 * (0.1627714 / Math.pow(slipperiness, 3)));
        }

        return movement;
    }

    protected void jump() {
        if (location.getBlock().isLiquid()) {
            // jump out more when you breach the surface of the liquid
            if (location.getBlock().getRelative(BlockFace.UP).isEmpty()) {
                velocity.setY(velocity.getY() + 0.3);
            }
            // less jumping in liquid
            velocity.setY(velocity.getY() + 0.04);
        } else {
            // jump normally
            velocity.setY(velocity.getY() + 0.42);
        }
    }

    @Override
    public boolean isJumping() {
        return jumping;
    }

    @Override
    public void setJumping(boolean jumping) {
        this.jumping = jumping;
    }

    @Override
    public void reset() {
        super.reset();
        equipmentMonitor.resetChanges();
        headRotated = false;
    }

    @Override
    public List<Message> createUpdateMessage(GlowSession session) {
        List<Message> messages = super.createUpdateMessage(session);

        messages.addAll(equipmentMonitor.getChanges().stream()
                .map(change -> new EntityEquipmentMessage(entityId, change.slot, change.item))
                .collect(Collectors.toList()));
        if (headRotated) {
            messages.add(new EntityHeadRotationMessage(entityId, Position.getIntHeadYaw(headYaw)));
        }
        attributeManager.applyMessages(messages);

        return messages;
    }

    ////////////////////////////////////////////////////////////////////////////
    // Properties

    @Override
    public double getEyeHeight() {
        return 0;
    }

    @Override
    public double getEyeHeight(boolean ignoreSneaking) {
        return getEyeHeight();
    }

    @NotNull
    @Override
    public Location getEyeLocation() {
        return getLocation().add(0, getEyeHeight(), 0);
    }

    @Override
    public boolean hasLineOfSight(@NotNull Entity other) {
        return false;
    }

    public void setHeadYaw(float headYaw) {
        this.headYaw = headYaw;
        this.headRotated = true;
    }

    ////////////////////////////////////////////////////////////////////////////
    // Properties

    @Override
    public EntityEquipment getEquipment() {
        return null;
    }

    @Override
    public void setRemainingAir(int ticks) {
        ticks = Math.min(ticks, maximumAir);
        if (ticks == remainingAir) {
            return;
        }
        EntityAirChangeEvent event = EventFactory.getInstance().callEvent(
                new EntityAirChangeEvent(this, remainingAir)
        );
        if (event.isCancelled()) {
            return;
        }
        remainingAir = event.getAmount();
    }

    @Override
    public void setMaximumAir(int ticks) {
        maximumAir = Math.max(0, ticks);
    }

    @Override
    public boolean getRemoveWhenFarAway() {
        return removeWhenFarAway;
    }

    @Override
    public boolean getCanPickupItems() {
        return canPickupItems;
    }

    /**
     * Get the hurt sound of this entity, or null for silence.
     *
     * @return the hurt sound if available
     */
    protected Sound getHurtSound() {
        return null;
    }

    /**
     * Get the death sound of this entity, or null for silence.
     *
     * @return the death sound if available
     */
    protected Sound getDeathSound() {
        return null;
    }

    /**
     * Get the ambient sound this entity makes randomly, or null for silence.
     *
     * @return the ambient sound if available
     */
    protected Sound getAmbientSound() {
        return null;
    }

    /**
     * Get the minimal delay until the entity can produce an ambient sound.
     *
     * @return the minimal delay until the entity can produce an ambient sound
     */
    protected int getAmbientDelay() {
        return 80;
    }

    /**
     * The volume of the sounds this entity makes.
     *
     * @return the volume of the sounds
     */
    protected float getSoundVolume() {
        return 1.0F;
    }

    /**
     * The pitch of the sounds this entity makes.
     *
     * @return the pitch of the sounds
     */
    protected float getSoundPitch() {
        return SoundUtil.randomReal(0.2F) + 1F;
    }

    /**
     * Get whether this entity should take damage from the specified source.
     *
     * <p>Usually used to check environmental sources such as drowning.
     *
     * @param damageCause the damage source to check
     * @return whether this entity can take damage from the source
     */
    public boolean canTakeDamage(DamageCause damageCause) {
        return true;
    }

    /**
     * Get whether of not this entity is an arthropod.
     *
     * @return true if this entity is an arthropod, false otherwise
     */
    public boolean isArthropod() {
        return false;
    }

    ////////////////////////////////////////////////////////////////////////////
    // Line of Sight

    /**
     * Get whether or not this entity is undead.
     *
     * @return true if this entity is undead, false otherwise
     */
    public boolean isUndead() {
        return false;
    }

    private List<Block> getLineOfSight(Set<Material> transparent, int maxDistance, int maxLength) {
        // same limit as CraftBukkit
        if (maxDistance > 120) {
            maxDistance = 120;
        }

        LinkedList<Block> blocks = new LinkedList<>();
        Iterator<Block> itr = new BlockIterator(this, maxDistance);
        while (itr.hasNext()) {
            Block block = itr.next();
            blocks.add(block);
            if (maxLength != 0 && blocks.size() > maxLength) {
                blocks.removeFirst();
            }
            Material material = block.getType();
            if (transparent == null) {
                if (material != Material.AIR) {
                    break;
                }
            } else {
                if (!transparent.contains(material)) {
                    break;
                }
            }
        }
        return blocks;
    }

    @NotNull
    @Override
    public List<Block> getLineOfSight(Set<Material> transparent, int maxDistance) {
        return getLineOfSight(transparent, maxDistance, 0);
    }

    @NotNull
    @Override
    public Block getTargetBlock(Set<Material> materials, int maxDistance) {
        return getLineOfSight(materials, maxDistance, 1).get(0);
    }

    // TODO: 1.13
    @Override
    public @Nullable Block getTargetBlock(int maxDistance,
                                          @NotNull TargetBlockInfo.FluidMode fluidMode) {
        return null;
    }

    @Override
    public @Nullable BlockFace getTargetBlockFace(int maxDistance,
                                                  @NotNull TargetBlockInfo.FluidMode fluidMode) {
        return null;
    }

    @Override
    public @Nullable TargetBlockInfo getTargetBlockInfo(int maxDistance,
                                                        @NotNull TargetBlockInfo.FluidMode fluidMode) {
        return null;
    }

    @Override
    public @Nullable Entity getTargetEntity(int maxDistance, boolean ignoreBlocks) {
        return null;
    }

    @Override
    public @Nullable TargetEntityInfo getTargetEntityInfo(int maxDistance, boolean ignoreBlocks) {
        return null;
    }

    @Override
    public @Nullable Block getTargetBlockExact(int maxDistance) {
        return null;
    }

    @Override
    public @Nullable Block getTargetBlockExact(int maxDistance,
                                               @NotNull FluidCollisionMode fluidCollisionMode) {
        return null;
    }

    @Override
    public @Nullable RayTraceResult rayTraceBlocks(double maxDistance) {
        return null;
    }

    @Override
    public @Nullable RayTraceResult rayTraceBlocks(double maxDistance,
                                                   @NotNull FluidCollisionMode fluidCollisionMode) {
        return null;
    }

    @NotNull
    @Override
    public List<Block> getLastTwoTargetBlocks(Set<Material> materials, int maxDistance) {
        return getLineOfSight(materials, maxDistance, 2);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Projectiles

    /**
     * Returns whether the entity's eye location is within a solid block.
     *
     * @return true if the entity is in a solid block; false otherwise
     */
    public boolean isWithinSolidBlock() {
        return getEyeLocation().getBlock().getType().isOccluding();
    }

    @NotNull
    @Override
    public <T extends Projectile> T launchProjectile(@NotNull Class<? extends T> type) {
        return launchProjectile(type,
                getLocation().getDirection());  // todo: multiply by some speed
    }

    @NotNull
    @Override
    public <T extends Projectile> T launchProjectile(@NotNull Class<? extends T> type,
                                                     Vector vector) {
        float offset = 0.0F;
        float speed = 1.5F;
        return launchProjectile(type, vector, offset, speed);
    }

    /**
     * Launches a projectile from this entity.
     *
     * @param type   the projectile class
     * @param vector the direction to shoot in
     * @param offset TODO: document this parameter
     * @param speed  the speed for the first flight tick
     * @param <T>    the projectile class
     * @return the launched projectile
     */
    public <T extends Projectile> T launchProjectile(Class<? extends T> type, Vector vector,
                                                     float offset, float speed) {
        if (vector == null) {
            vector = getVelocity();
        }

        T projectile = launchProjectile(type, getEyeLocation().clone(), vector, offset, speed);
        projectile.setShooter(this);
        return projectile;
    }

    /**
     * Launches a projectile from this entity in the horizontal direction it is facing, relative to
     * the given velocity vector.
     *
     * @param type           the projectile class
     * @param location       the location to launch the projectile from
     * @param originalVector the direction to shoot in
     * @param pitchOffset    degrees to subtract from the pitch angle while calculating the y
     *                       component of the initial direction
     * @param velocity       the speed for the first flight tick
     * @param <T>            the projectile class
     * @return the launched projectile
     */
    protected <T extends Projectile> T launchProjectile(Class<? extends T> type, Location location,
                                                        Vector originalVector, float pitchOffset,
                                                        float velocity) {
        double pitchRadians = Math.toRadians(location.getPitch());
        double yawRadians = Math.toRadians(location.getYaw());

        double verticalMultiplier = cos(pitchRadians);
        double x = verticalMultiplier * sin(-yawRadians);
        double z = verticalMultiplier * cos(yawRadians);
        double y = sin(-(Math.toRadians(location.getPitch() - pitchOffset)));

        T projectile = launchProjectile(type, location, x, y, z, velocity);
        projectile.getVelocity().add(originalVector);
        return projectile;
    }

    /**
     * Throws and returns a projectile, initializing its velocity.
     *
     * @param type     a projectile class that can be passed to
     *                 {@link org.bukkit.World#spawn(Location, Class)}
     * @param location initial location
     * @param x        x component of direction (doesn't need to be normalized)
     * @param y        y component of direction (doesn't need to be normalized)
     * @param z        z component of direction (doesn't need to be normalized)
     * @param speed    speed
     * @param <T>      the projectile class
     * @return the newly launched projectile
     */
    private <T extends Projectile> T launchProjectile(Class<? extends T> type, Location location,
                                                      double x, double y, double z, float speed) {
        double magnitude = Math.sqrt(x * x + y * y + z * z);
        if (magnitude > 0) {
            x += (x * (speed - magnitude)) / magnitude;
            y += (y * (speed - magnitude)) / magnitude;
            z += (z * (speed - magnitude)) / magnitude;
        }

        location.add(location.getDirection());
        location.setPitch(0);
        location.setYaw(0);

        T projectile = location.getWorld().spawn(location, type);

        ProjectileLaunchEvent launchEvent = EventFactory.getInstance()
                .callEvent(new ProjectileLaunchEvent(projectile));
        if (launchEvent.isCancelled()) {
            projectile.remove();
        }
        projectile.setVelocity(new Vector(x, y, z));
        ((GlowProjectile) projectile).setRawLocation(location);
        return projectile;
    }

    ////////////////////////////////////////////////////////////////////////////
    // Health

    @Override
    public void setHealth(double health) {
        if (health < 0) {
            health = 0;
        }
        if (health > getMaxHealth()) {
            health = getMaxHealth();
        }
        this.health = health;
        metadata.set(MetadataIndex.HEALTH, (float) health);
        for (Objective objective : getServer().getScoreboardManager().getMainScoreboard()
                .getObjectivesByCriteria(Criterias.HEALTH)) {
            objective.getScore(getName()).setScore((int) health);
        }
        if (health > 0) {
            return;
        }

        if (this.tryUseTotem()) {
            return;
        }

        // Killed
        active = false;
        Sound deathSound = getDeathSound();
        if (deathSound != null && !isSilent()) {
            world.playSound(location, deathSound, getSoundVolume(), getSoundPitch());
        }
        playEffectKnownAndSelf(EntityEffect.DEATH);
        if (this instanceof GlowPlayer) {
            GlowPlayer player = (GlowPlayer) this;
            List<ItemStack> items = null;
            boolean dropInventory = !world.getGameRuleMap().getBoolean(GameRules.KEEP_INVENTORY);
            if (dropInventory) {
                items = Arrays.stream(player.getInventory().getContents())
                        .filter(stack -> !InventoryUtil.isEmpty(stack))
                        .collect(Collectors.toList());
                player.getInventory().clear();
            }
            PlayerDeathEvent event = new PlayerDeathEvent(player, items, 0,
                    player.getDisplayName() + " died.");
            EventFactory.getInstance().callEvent(event);
            server.broadcastMessage(event.getDeathMessage());
            if (dropInventory) {
                for (ItemStack item : items) {
                    world.dropItemNaturally(getLocation(), item);
                }
            }
            player.setShoulderEntityRight(null);
            player.setShoulderEntityLeft(null);
            player.incrementStatistic(Statistic.DEATHS);
        } else {
            EntityDeathEvent deathEvent = new EntityDeathEvent(this, new ArrayList<>());
            if (world.getGameRuleMap().getBoolean(GameRules.DO_MOB_LOOT)) {
                LootData data = LootingManager.generate(this);
                deathEvent.getDrops().addAll(data.getItems());
                // Only drop experience when hit by a player within 5 seconds (100 game ticks)
                if (ticksLived - playerDamageTick <= 100 && data.getExperience() > 0) {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    ExperienceSplitter.forEachCut(data.getExperience(), exp -> {
                        double modX = random.nextDouble() - 0.5;
                        double modZ = random.nextDouble() - 0.5;
                        Location xpLocation = new Location(world,
                                location.getBlockX() + 0.5 + modX, location.getY(),
                                location.getBlockZ() + 0.5 + modZ);
                        GlowExperienceOrb orb = (GlowExperienceOrb) world
                                .spawnEntity(xpLocation, EntityType.EXPERIENCE_ORB);
                        orb.setExperience(exp);
                        orb.setSourceEntityId(this.getUniqueId());
                        if (getLastDamager() != null) {
                            orb.setTriggerEntityId(getLastDamager().getUniqueId());
                        }
                    });
                }
            }
            deathEvent = EventFactory.getInstance().callEvent(deathEvent);
            for (ItemStack item : deathEvent.getDrops()) {
                world.dropItemNaturally(getLocation(), item);
            }
        }

        // TODO: Add a die method to GlowEntity class and override in
        // various subclasses depending on the actions needed to be run
        // to help keep code maintainable
        if (this instanceof GlowSlime) {
            GlowSlime slime = (GlowSlime) this;

            int size = slime.getSize();
            if (size > 1) {
                int count = 2 + ThreadLocalRandom.current().nextInt(3);

                SlimeSplitEvent event = EventFactory.getInstance().callEvent(
                        new SlimeSplitEvent(slime, count));
                if (event.isCancelled() || event.getCount() <= 0) {
                    return;
                }

                count = event.getCount();
                for (int i = 0; i < count; ++i) {
                    Location spawnLoc = getLocation().clone();
                    spawnLoc.add(
                            ThreadLocalRandom.current().nextDouble(0.5, 3),
                            0,
                            ThreadLocalRandom.current().nextDouble(0.5, 3)
                    );

                    GlowSlime splitSlime = (GlowSlime) world.spawnEntity(
                            spawnLoc, EntityType.SLIME);

                    // Make the split slime the same name as the killed slime.
                    if (getCustomName() != null && !getCustomName().isEmpty()) {
                        splitSlime.setCustomName(getCustomName());
                    }

                    splitSlime.setSize(size / 2);
                }
            }
        }
    }

    @Override
    public void damage(double amount, Entity source, @NotNull DamageCause cause) {
        // invincibility timer
        if (noDamageTicks > 0 || health <= 0 || !canTakeDamage(cause) || isInvulnerable()) {
            return;
        } else {
            noDamageTicks = maximumNoDamageTicks;
        }

        // fire resistance
        if (hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) {
            if (source instanceof Fireball) {
                return;
            } else {
                switch (cause) {
                    case FIRE:
                    case FIRE_TICK:
                    case HOT_FLOOR:
                    case LAVA:
                        return;
                    default:
                        // Not fire damage; continue
                }
            }
        }

        // armor damage protection
        // formula source: http://minecraft.gamepedia.com/Armor#Damage_Protection
        double defensePoints = getAttributeManager().getPropertyValue(Key.KEY_ARMOR);
        double toughness = getAttributeManager().getPropertyValue(Key.KEY_ARMOR_TOUGHNESS);
        amount = amount * (1 - Math.min(20.0,
                Math.max(defensePoints / 5.0,
                        defensePoints - amount / (2.0 + toughness / 4.0))) / 25);

        // fire event
        EntityDamageEvent event = EventFactory.getInstance().onEntityDamage(source == null
                ? new EntityDamageEvent(this, cause, amount)
                : new EntityDamageByEntityEvent(source, this, cause, amount));
        if (event.isCancelled()) {
            return;
        }

        // apply damage
        amount = event.getFinalDamage();
        lastDamage = amount;

        if (isPlayerHit(source)) {
            playerDamageTick = ticksLived;
            if (health - amount <= 0) {
                killer = determinePlayer(source);
                if (killer != null) {
                    killer.incrementStatistic(Statistic.KILL_ENTITY, getType());
                }
            }
        }

        setHealth(health - amount);
        playEffectKnownAndSelf(EntityEffect.HURT);

        if (cause == DamageCause.ENTITY_ATTACK && source != null) {
            Vector distance = RayUtil
                    .getRayBetween(getLocation(), ((LivingEntity) source).getEyeLocation());

            Vector rayLength = RayUtil.getVelocityRay(distance).normalize();

            Vector currentVelocity = getVelocity();
            currentVelocity.add(rayLength.multiply(((amount + 1) / 2d)));
            setVelocity(currentVelocity);
        }

        // play sounds, handle death
        if (health > 0) {
            Sound hurtSound = getHurtSound();
            if (hurtSound != null && !isSilent()) {
                world.playSound(location, hurtSound, getSoundVolume(), getSoundPitch());
            }
        }
        setLastDamager(source);
    }

    /**
     * Checks if the source of damage was caused by a player.
     *
     * @param source The source of damage
     * @return true if the source of damage was caused by a player, false otherwise.
     */
    private boolean isPlayerHit(Entity source) {
        // If directly damaged by a player
        if (source instanceof GlowPlayer) {
            return true;
        }

        // If damaged by a TNT ignited by a player
        if (source instanceof GlowTntPrimed) {
            GlowPlayer player = (GlowPlayer) ((GlowTntPrimed) source).getSource();
            return
                    player != null
                            && (player.getGameMode() == GameMode.SURVIVAL
                            || player.getGameMode() == GameMode.ADVENTURE);
        }

        // If damaged by a tamed wolf
        if (source instanceof GlowWolf) {
            return ((GlowWolf) source).isTamed();
        }

        // All other cases
        return false;
    }

    /**
     * Determines the player who did the damage from source of damage.
     *
     * @param source The incoming source of damage
     * @return Player object if the source of damage was caused by a player, null otherwise.
     */
    private Player determinePlayer(Entity source) {
        // If been killed by an ignited tnt
        if (source instanceof GlowTntPrimed) {
            return (Player) ((GlowTntPrimed) source).getSource();
        }

        // If been killed by a player
        if (source instanceof GlowPlayer) {
            return (Player) source;
        }

        // If been killed by a tamed wolf
        if (source instanceof GlowWolf) {
            return (Player) ((GlowWolf) source).getOwner();
        }

        // All other cases
        return null;
    }

    @Override
    public double getMaxHealth() {
        return attributeManager.getPropertyValue(Key.KEY_MAX_HEALTH);
    }

    @Override
    public void setMaxHealth(double health) {
        attributeManager.setProperty(Key.KEY_MAX_HEALTH, health);
    }

    @Override
    public void resetMaxHealth() {
        setMaxHealth(maxHealth);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Potion effects

    @Override
    public boolean addPotionEffect(PotionEffect effect) {
        return addPotionEffect(effect, false);
    }

    @Override
    public boolean addPotionEffect(PotionEffect effect, boolean force) {
        if (potionEffects.containsKey(effect.getType())) {
            if (force) {
                removePotionEffect(effect.getType());
            } else {
                return false;
            }
        }

        potionEffects.put(effect.getType(), effect);

        potionEffectsChanged = true;

        EntityEffectMessage msg = new EntityEffectMessage(getEntityId(), effect.getType().getId(),
                effect.getAmplifier(), effect.getDuration(), effect.hasParticles(), effect.isAmbient());
        for (GlowPlayer player : world.getRawPlayers()) {
            if (player.canSeeEntity(this) || player == this) {
                player.getSession().send(msg);
            }
        }
        return true;
    }

    @Override
    public boolean addPotionEffects(Collection<PotionEffect> effects) {
        boolean result = true;
        for (PotionEffect effect : effects) {
            if (!addPotionEffect(effect)) {
                result = false;
            }
        }
        return result;
    }

    @Override
    public boolean hasPotionEffect(PotionEffectType type) {
        return potionEffects.containsKey(type);
    }

    @Override
    public PotionEffect getPotionEffect(PotionEffectType potionEffectType) {
        return potionEffects.get(potionEffectType);
    }

    @Override
    public void removePotionEffect(PotionEffectType type) {
        if (!hasPotionEffect(type)) {
            return;
        }
        potionEffects.remove(type);

        potionEffectsChanged = true;

        EntityRemoveEffectMessage msg = new EntityRemoveEffectMessage(getEntityId(), type.getId());
        for (GlowPlayer player : world.getRawPlayers()) {
            if (player.canSeeEntity(this) || player == this) {
                player.getSession().send(msg);
            }
        }
    }

    @Override
    public Collection<PotionEffect> getActivePotionEffects() {
        return Collections.unmodifiableCollection(potionEffects.values());
    }

    public void clearActivePotionEffects() {
        for (PotionEffect effect : this.getActivePotionEffects()) {
            this.removePotionEffect(effect.getType());
        }
    }

    protected void updatePotionEffectsMetadata() {
        int color = 0;
        if (this.potionEffects.size() > 0) {
            Color[] colors = this.potionEffects.values().stream()
                    .filter(PotionEffect::hasParticles)
                    .map(effect -> effect.getColor() != null ? effect.getColor() :
                            effect.getType().getColor())
                    .toArray(Color[]::new);
            color = Color.BLACK.mixColors(colors).asRGB();
        }
        metadata.set(MetadataIndex.POTION_COLOR, color); //TODO: calculate color like in vanilla
        metadata.set(MetadataIndex.POTION_AMBIENT,
                this.potionEffects.values().stream().allMatch(PotionEffect::isAmbient));
    }

    @Override
    public void setOnGround(boolean onGround) {
        float fallDistance = getFallDistance();
        if (onGround && fallDistance > 3f) {
            float damage = fallDistance - 3f;
            damage = Math.round(damage);
            if (damage > 0f) {
                Material standingType = location.getBlock().getRelative(BlockFace.DOWN).getType();
                // todo: only when bouncing
                if (standingType == Material.SLIME_BLOCK) {
                    damage = 0f;
                }

                if (standingType == Material.HAY_BLOCK) {
                    damage *= 0.2f;
                }

                damage(damage, DamageCause.FALL);
            }
        }
        super.setOnGround(onGround);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Leashes

    @Override
    public boolean isGliding() {
        return metadata.getBit(MetadataIndex.STATUS, MetadataIndex.StatusFlags.GLIDING);
    }

    @Override
    public void setGliding(boolean gliding) {
        if (EventFactory.getInstance().callEvent(
                new EntityToggleGlideEvent(this, gliding)).isCancelled()) {
            return;
        }

        metadata.setBit(MetadataIndex.STATUS, MetadataIndex.StatusFlags.GLIDING, gliding);
    }

    @Override
    public int getShieldBlockingDelay() {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public void setShieldBlockingDelay(int delay) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public ItemStack getActiveItem() {
        return null;
    }

    @Override
    public void clearActiveItem() {
        // TODO: 1.16
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public int getItemUseRemainingTime() {
        return 0;
    }

    @Override
    public int getHandRaisedTime() {
        return 0;
    }

    @Override
    public boolean isHandRaised() {
        return false;
    }

    @Override
    public boolean isSwimming() {
        // TODO: 1.13
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public void setSwimming(boolean swimming) {
        // TODO: 1.13
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public boolean isRiptiding() {
        // TODO: 1.13
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public boolean isSleeping() {
        // TODO: 1.16
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    /**
     * Sets the AI state.
     *
     * @param state the new AI state
     */
    public void setState(MobState state) {
        if (this.state != state) {
            this.state = state;
            getTaskManager().updateState();
        }
    }

    @Override
    public void setAI(boolean ai) {
        if (ai) {
            if (state == MobState.NO_AI) {
                setState(MobState.IDLE);
            }
        } else {
            setState(MobState.NO_AI);
        }
    }

    @Override
    public boolean hasAI() {
        return state != MobState.NO_AI;
    }

    @Override
    public void attack(@NotNull Entity entity) {
        // TODO: 1.16
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public void swingMainHand() {
        // TODO: 1.16
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public void swingOffHand() {
        // TODO: 1.16
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public @NotNull Set<UUID> getCollidableExemptions() {
        // TODO: 1.16
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public <T> @Nullable T getMemory(@NotNull MemoryKey<T> memoryKey) {
        // TODO: 1.16
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public <T> void setMemory(@NotNull MemoryKey<T> memoryKey, @Nullable T t) {
        // TODO: 1.16
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public @NotNull EntityCategory getCategory() {
        // TODO: Overload this
        return EntityCategory.NONE;
    }

    public void playAnimation(@NotNull EntityAnimation animation) {
        EntityAnimationMessage message =
                new EntityAnimationMessage(getEntityId(), animation.ordinal());

        getWorld().getRawPlayers().stream()
                .filter(observer -> observer != this && observer.canSeeEntity(this))
                .forEach(observer -> observer.getSession().send(message));
    }

    @Nullable
    @Override
    public AttributeInstance getAttribute(@NotNull Attribute attribute) {
        Key attributeKey = Key.fromAttribute(attribute);
        if (attributeKey != null) {
            return getAttributeManager().getProperty(attributeKey);
        }
        return null;
    }

    @Override
    public void registerAttribute(@NotNull Attribute attribute) {
        // TODO: Determine whether this is needed; our attribute system is pretty leniert and doesn't need registration
    }

    @Override
    public boolean entityInteract(GlowPlayer player, InteractEntityMessage message) {
        super.entityInteract(player, message);

        if (message.getAction() != Action.INTERACT.ordinal()) {
            return false;
        }

        ItemStack handItem = InventoryUtil
                .itemOrEmpty(player.getInventory().getItem(message.getHandSlot()));
        if (isLeashed() && player.equals(this.getLeashHolder())
                && message.getHandSlot() == EquipmentSlot.HAND) {
            if (EventFactory.getInstance()
                    .callEvent(new PlayerUnleashEntityEvent(this, player)).isCancelled()) {
                return false;
            }

            setLeashHolder(null);
            if (player.getGameMode() != GameMode.CREATIVE) {
                world.dropItemNaturally(this.location, new ItemStack(Material.LEAD));
            }
            return true;
        } else if (!InventoryUtil.isEmpty(handItem) && handItem.getType() == Material.LEAD) {
            if (!GlowLeashHitch.isAllowedLeashHolder(this.getType()) || this.isLeashed()
                    || EventFactory.getInstance().callEvent(
                            new PlayerLeashEntityEvent(this, player, player))
                    .isCancelled()) {
                return false;
            }

            if (player.getGameMode() != GameMode.CREATIVE) {
                if (handItem.getAmount() > 1) {
                    handItem.setAmount(handItem.getAmount() - 1);
                } else {
                    handItem = InventoryUtil.createEmptyStack();
                }
                player.getInventory().setItem(message.getHandSlot(), handItem);
            }

            setLeashHolder(player);
            return true;
        }

        return false;
    }

    /**
     * Use "Totem of Undying" if equipped
     *
     * @return result of totem use
     */
    public boolean tryUseTotem() {
        //TODO: Should return false if player die in void.
        if (!(this instanceof HumanEntity)) {
            return false;
        }

        HumanEntity human = (HumanEntity) this;
        ItemStack mainHand = human.getInventory().getItemInMainHand();
        ItemStack offHand = human.getInventory().getItemInOffHand();

        boolean hasTotem = false;
        if (!InventoryUtil.isEmpty(mainHand) && mainHand.getType() == Material.TOTEM_OF_UNDYING) {
            mainHand.setAmount(mainHand.getAmount() - 1);
            human.getInventory().setItemInMainHand(InventoryUtil.createEmptyStack());
            hasTotem = true;
        } else if (!InventoryUtil.isEmpty(offHand)
                && offHand.getType() == Material.TOTEM_OF_UNDYING) {
            human.getInventory().setItemInOffHand(InventoryUtil.createEmptyStack());
            hasTotem = true;
        }

        EntityResurrectEvent event =
                EventFactory.getInstance().callEvent(new EntityResurrectEvent(this));
        event.setCancelled(!hasTotem);

        if (event.isCancelled()) {
            return false;
        }

        this.setHealth(1.0F);
        this.clearActivePotionEffects();
        this.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        this.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 900, 1));
        playEffectKnownAndSelf(EntityEffect.TOTEM_RESURRECT);

        return true;
    }

    @Override
    public void playPickupItemAnimation(@NotNull Item item, int quantity) {
        CollectItemMessage message =
                new CollectItemMessage(item.getEntityId(), getEntityId(), quantity);
        world.playSound(location, Sound.ENTITY_ITEM_PICKUP, 0.3f, (float) (1 + Math.random()));
        world.getRawPlayers().stream().filter(other -> other.canSeeEntity(this))
                .forEach(other -> other.getSession().send(message));
        item.remove();
    }
}
