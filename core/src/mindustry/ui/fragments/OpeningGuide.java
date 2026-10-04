package mindustry.ui.fragments;

import arc.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;

import static mindustry.Vars.*;

/** 零号地区开局的短步骤。每步一个可点目标，不靠长说明。 */
public class OpeningGuide{
    private static final String doneKey = "opening-guide-done";
    private static final float moveDst = 6f * tilesize;

    public Group group = new WidgetGroup();

    private boolean spotsReady, moveMarked, hasOre, hasDrillSpot, hasSpawn, hasDuo, hasCoal;
    private int baselineCopper, baselineLead;
    private boolean baselineSet;
    private float moveX, moveY;
    private final Vec2 orePos = new Vec2(), drillPos = new Vec2(), spawnPos = new Vec2(), duoPos = new Vec2(), movePos = new Vec2();
    private Step labelStep;
    private String cachedText;
    private Step cachedStep;

    public OpeningGuide(){
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, () -> {
            if(!active()) return;
            Step step = current();
            Draw.draw(Layer.overlayUI, () -> drawMark(step));
        });
        Events.run(Trigger.uiDrawEnd, this::drawPulse);
        Events.on(WorldLoadEvent.class, e -> resetWorld());
        Events.on(ResetEvent.class, e -> resetWorld());
    }

    public void build(Group parent){
        group.setFillParent(true);
        group.touchable = Touchable.childrenOnly;
        group.visibility = () -> active() && !Core.scene.hasDialog();
        parent.addChild(group);
    }

    /** 科技树里需要高亮的内容；没有则为 null。 */
    public @Nullable UnlockableContent highlight(){
        Step step = labelStep;
        if(step == Step.researchDrill) return Blocks.mechanicalDrill;
        if(step == Step.researchConveyor) return Blocks.conveyor;
        if(step == Step.researchDuo) return Blocks.duo;
        if(step == Step.power) return Blocks.combustionGenerator;
        return null;
    }

    public boolean active(){
        return eligible() && current() != null;
    }

    private void update(){
        if(group.parent == null) return;

        if(!eligible()){
            if(labelStep != null) syncLabel(null);
            return;
        }

        captureBaseline();
        scan();

        UnlockableContent focus = highlight();
        if(focus != null && ui.research.isShown()){
            ui.research.panTo(focus);
        }

        if(ui.hints.shown()){
            ui.hints.hide();
        }

        Step step = current();
        syncLabel(step);
        if(step == null && spotsReady){
            finish();
        }
    }

    private void syncLabel(Step step){
        if(step == labelStep) return;
        labelStep = step;
        cachedStep = null;
        group.clearChildren();
        if(step == null) return;

        group.fill(t -> {
            t.top().left();
            t.table(Styles.black5, cont -> {
                cont.margin(6f).add(text(step)).width(mobile ? 230f : 280f).left().labelAlign(Align.left).wrap();
            }).padTop(mobile ? 78f : 108f).padLeft(8f);
            t.row();
            t.button("@hint.skip", Styles.nonet, this::finish).size(112f, 40f).left().padLeft(8f);
        });
    }

    private void finish(){
        if(Core.settings.getBool(doneKey, false)) return;
        Core.settings.put(doneKey, true);
        labelStep = null;
        group.clearChildren();
    }

    private boolean eligible(){
        return Core.settings.getBool("hints", true)
            && !Core.settings.getBool(doneKey, false)
            && state.isGame() && state.isCampaign() && !net.active()
            && state.rules.sector != null && state.rules.sector.preset == SectorPresets.groundZero
            && !renderer.isCutscene();
    }

    private Step current(){
        // 波次刷出时 wave 会先加一，所以用场上敌人而不是 wave == 1 判断第一波。
        if(state.enemies > 0 && state.stats.enemyUnitsDestroyed <= 0) return Step.wave;
        if(!moved()) return Step.move;
        if(!gathered()) return Step.mine;
        if(carrying()) return Step.deposit;
        if(!Blocks.mechanicalDrill.unlocked()) return Step.researchDrill;
        if(!hasPlan(Blocks.mechanicalDrill)) return Step.placeDrill;
        if(!Blocks.conveyor.unlocked()) return Step.researchConveyor;
        if(!hasPlan(Blocks.conveyor)) return Step.placeConveyor;
        if(!Blocks.duo.unlocked()) return Step.researchDuo;
        if(!hasPlan(Blocks.duo)) return Step.placeDuo;
        // 零号地区开局没有煤，发电机不会出现；只有已经解锁才提示接电。
        if(hasCoal && Blocks.combustionGenerator.unlocked() && !hasPlan(Blocks.combustionGenerator)) return Step.power;
        if(state.stats.enemyUnitsDestroyed <= 0 && !(state.wave >= 2 && state.enemies == 0)){
            return Step.waveWait;
        }
        return null;
    }

    private boolean alive(){
        return player != null && player.unit() != null && !player.dead();
    }

    private boolean moved(){
        if(hasPlan(Blocks.mechanicalDrill) || hasPlan(Blocks.conveyor) || hasPlan(Blocks.duo)) return true;
        if(!moveMarked || !alive()) return false;
        return player.dst(moveX, moveY) > moveDst;
    }

    private boolean gathered(){
        if(hasPlan(Blocks.mechanicalDrill)) return true;
        if(spotsReady && !hasOre) return true;
        if(carrying()) return true;
        if(!baselineSet) return false;
        Building core = player.core();
        if(core == null || core.items == null) return false;
        return core.items.get(Items.copper) + core.items.get(Items.lead) > baselineCopper + baselineLead;
    }

    private boolean carrying(){
        return alive() && player.unit().stack.amount > 0;
    }

    private boolean hasPlan(Block block){
        if(player != null && player.team() != null && player.team().data().getCount(block) > 0) return true;
        if(state.stats.placedBlockCount.get(block, 0) > 0) return true;
        if(control.input == null) return false;

        Seq<BuildPlan> plans = control.input.selectPlans;
        for(int i = 0; i < plans.size; i++){
            BuildPlan plan = plans.get(i);
            if(plan.block == block && !plan.breaking) return true;
        }
        if(!alive()) return false;
        Queue<BuildPlan> unitPlans = player.unit().plans();
        for(int i = 0; i < unitPlans.size; i++){
            BuildPlan plan = unitPlans.get(i);
            if(plan.block == block && !plan.breaking) return true;
        }
        return false;
    }

    private void captureBaseline(){
        if(baselineSet || !alive()) return;
        if(!moveMarked){
            moveX = player.x;
            moveY = player.y;
            moveMarked = true;
        }
        Building core = player.core();
        if(core == null || core.items == null) return;
        baselineCopper = core.items.get(Items.copper);
        baselineLead = core.items.get(Items.lead);
        baselineSet = true;
    }

    private void resetWorld(){
        spotsReady = moveMarked = baselineSet = false;
        hasOre = hasDrillSpot = hasSpawn = hasDuo = hasCoal = false;
        cachedStep = null;
    }

    private void scan(){
        if(spotsReady) return;
        if(world == null || world.width() == 0) return;
        Building core = state.rules.defaultTeam.core();
        if(core == null) return;

        int w = world.width(), h = world.height();
        float bestOre = Float.MAX_VALUE;
        boolean sawLead = false;
        float leadX = 0f, leadY = 0f, leadDst = Float.MAX_VALUE;
        float bestSpawn = Float.MAX_VALUE;
        float bestDrill = -Float.MAX_VALUE;

        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                Tile tile = world.tile(x, y);
                if(tile == null) continue;

                if(tile.overlay() == Blocks.spawn){
                    float dst = Mathf.dst(tile.worldx(), tile.worldy(), core.x, core.y);
                    if(dst < bestSpawn){
                        bestSpawn = dst;
                        spawnPos.set(tile.worldx(), tile.worldy());
                        hasSpawn = true;
                    }
                }

                if(tile.block() != Blocks.air) continue;
                Item drop = tile.drop();
                if(drop == Items.coal) hasCoal = true;
                if(drop == Items.copper || drop == Items.lead){
                    float dst = Mathf.dst(tile.worldx(), tile.worldy(), core.x, core.y);
                    if(drop == Items.copper && dst < bestOre){
                        bestOre = dst;
                        orePos.set(tile.worldx(), tile.worldy());
                        hasOre = true;
                    }else if(drop == Items.lead && dst < leadDst){
                        leadDst = dst;
                        leadX = tile.worldx();
                        leadY = tile.worldy();
                        sawLead = true;
                    }
                }
            }
        }

        if(!hasOre && sawLead){
            orePos.set(leadX, leadY);
            hasOre = true;
        }

        for(int y = 0; y < h - 1; y++){
            for(int x = 0; x < w - 1; x++){
                int copper = 0, lead = 0;
                boolean ok = true;
                for(int dy = 0; dy < 2 && ok; dy++){
                    for(int dx = 0; dx < 2; dx++){
                        Tile tile = world.tile(x + dx, y + dy);
                        if(tile == null || tile.block() != Blocks.air || tile.floor().isLiquid){
                            ok = false;
                            break;
                        }
                        Item drop = tile.drop();
                        if(drop == Items.copper) copper++;
                        else if(drop == Items.lead) lead++;
                    }
                }
                int score = copper * 2 + lead;
                if(!ok || score == 0) continue;
                float wx = (x + 0.5f) * tilesize, wy = (y + 0.5f) * tilesize;
                float rank = score * 80f - Mathf.dst(wx, wy, core.x, core.y);
                if(rank > bestDrill){
                    bestDrill = rank;
                    drillPos.set(wx, wy);
                    hasDrillSpot = true;
                }
            }
        }

        float face = hasSpawn ? core.angleTo(spawnPos) : 0f;
        for(int i = 0; i < 8; i++){
            Tmp.v1.trns(face + i * 45f, 6f * tilesize).add(core.x, core.y);
            Tile tile = world.tileWorld(Tmp.v1.x, Tmp.v1.y);
            if(tile != null && tile.block() == Blocks.air && !tile.floor().isLiquid){
                duoPos.set(Tmp.v1);
                hasDuo = true;
                break;
            }
        }

        float away = hasSpawn ? face + 180f : 90f;
        for(int i = 0; i < 8; i++){
            Tmp.v1.trns(away + i * 45f, 7f * tilesize).add(core.x, core.y);
            Tile tile = world.tileWorld(Tmp.v1.x, Tmp.v1.y);
            if(tile != null && !tile.solid() && !tile.floor().isLiquid){
                movePos.set(Tmp.v1);
                break;
            }
        }

        if(core.items != null && core.items.get(Items.coal) > 0) hasCoal = true;
        spotsReady = true;
    }

    private void drawMark(Step step){
        if(step == null) return;
        Building core = state.rules.defaultTeam.core();

        if(step == Step.move){
            ring(movePos.x, movePos.y);
        }else if(step == Step.mine && hasOre){
            ring(orePos.x, orePos.y);
        }else if(step == Step.deposit && core != null){
            ring(core.x, core.y);
        }else if(step == Step.placeDrill && hasDrillSpot){
            ring(drillPos.x, drillPos.y);
        }else if(step == Step.placeConveyor && core != null){
            float x = drillPos.x, y = drillPos.y;
            if(player != null && player.team() != null){
                var builds = player.team().data().getBuildings(Blocks.mechanicalDrill);
                if(builds.size > 0){
                    x = builds.first().x;
                    y = builds.first().y;
                }
            }
            Draw.z(Layer.overlayUI);
            Lines.stroke(2f, Pal.accent);
            Lines.line(x, y, core.x, core.y);
            ring(x, y);
        }else if(step == Step.placeDuo && hasDuo){
            ring(duoPos.x, duoPos.y);
        }else if(step == Step.power && hasDrillSpot){
            ring(drillPos.x, drillPos.y);
        }else if(step == Step.wave || step == Step.waveWait){
            if(alive() && state.enemies > 0){
                Unit enemy = Units.closestEnemy(player.team(), player.x, player.y, 2000f, u -> true);
                if(enemy != null){
                    ring(enemy.x, enemy.y);
                    return;
                }
            }
            if(hasSpawn) ring(spawnPos.x, spawnPos.y);
        }
    }

    private void ring(float x, float y){
        float fin = (Time.globalTime / 70f) % 1f;
        Draw.z(Layer.overlayUI);
        Lines.stroke((1f - fin) * 3f + 1f, Pal.accent);
        Lines.circle(x, y, 6f + fin * 14f);
        Draw.reset();
    }

    private void drawPulse(){
        if(!active() || Core.scene == null) return;
        String name = pulseName();
        if(name == null) return;
        Element elem = Core.scene.find(name);
        if(elem == null || !elem.visible || elem.getWidth() <= 1f) return;

        Vec2 center = elem.localToStageCoordinates(Tmp.v1.set(elem.getWidth() / 2f, elem.getHeight() / 2f));
        Draw.proj(Core.scene.getCamera());
        float rad = Math.max(elem.getWidth(), elem.getHeight()) * 0.62f + Mathf.absin(Time.globalTime, 6f, 4f);
        Lines.stroke(3f, Pal.accent);
        Lines.circle(center.x, center.y, rad);
        Draw.reset();
    }

    private @Nullable String pulseName(){
        Step step = labelStep;
        if(step == Step.researchDrill || step == Step.researchConveyor || step == Step.researchDuo){
            return ui.research.isShown() ? null : "research";
        }
        if(step == Step.placeDrill) return placePulse(Category.production, Blocks.mechanicalDrill);
        if(step == Step.placeConveyor) return placePulse(Category.distribution, Blocks.conveyor);
        if(step == Step.placeDuo) return placePulse(Category.turret, Blocks.duo);
        if(step == Step.power) return placePulse(Category.power, Blocks.combustionGenerator);
        return null;
    }

    private String placePulse(Category category, Block block){
        if(ui.hudfrag.blockfrag.currentCategory != category) return "category-" + category.name();
        return "block-" + block.name;
    }

    private String text(Step step){
        if(step == cachedStep && cachedText != null) return cachedText;
        String key = "opening." + step.name();
        if(mobile && Core.bundle.has(key + ".mobile")){
            key += ".mobile";
        }else if(!mobile && Core.bundle.has(key + ".desktop")){
            key += ".desktop";
        }
        cachedStep = step;
        cachedText = UI.formatIcons(Core.bundle.get(key));
        return cachedText;
    }

    private enum Step{
        move,
        mine,
        deposit,
        researchDrill,
        placeDrill,
        researchConveyor,
        placeConveyor,
        researchDuo,
        placeDuo,
        power,
        waveWait,
        wave
    }
}
