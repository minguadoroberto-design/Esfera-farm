package br.esferasauto;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Esferas do Dragao (servidor Rede Warriors + DragonMineZ).
 * Tecla K: le as posicoes das esferas pelo proprio mod do servidor, liga o voo (DragonMineZ),
 * voa ate a esfera mais proxima, encosta nela e passa para a proxima. K de novo para.
 * Requisito: estar no Mundo de Historia, na Terra ou em Namek.
 */
public final class EsferasAuto {
    static final String EC = "com.nonopichy.yggdrasilremake.client.warriors.esferas.EsferasClient";
    static final String FLY = "com.dragonminez.client.events.FlySkillEvent";
    static final String PROV = "com.dragonminez.common.stats.StatsProvider";
    static final String CAP = "com.dragonminez.common.stats.StatsCapability";

    static final KeyMapping KEY = new KeyMapping("Esferas: ligar/desligar", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "Esferas AutoFarm");

    enum S { OFF, SYNC, FLYON, GO, TOUCH, UP }
    static S s = S.OFF;
    static byte planeta;
    static int wait, t, ticksBola, quedas;
    static int alvoEstrelas;
    static Vec3 ultimaPos = Vec3.ZERO;
    static final Set<Integer> ignoradas = new HashSet<>();

    private EsferasAuto() {}

    @Mod.EventBusSubscriber(modid = "esferasauto", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void teclas(RegisterKeyMappingsEvent e) { e.register(KEY); }
    }

    @Mod.EventBusSubscriber(modid = "esferasauto", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent e) {
            if (e.phase == TickEvent.Phase.END) EsferasAuto.tick(Minecraft.getInstance());
        }
    }

    // ---------------- loop principal ----------------
    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) { if (s != S.OFF) { soltar(mc); s = S.OFF; } return; }
        while (KEY.consumeClick()) {
            if (s == S.OFF) iniciar(mc); else parar(mc, "Parado.");
        }
        if (s == S.OFF) return;
        if (mc.screen != null) { parar(mc, "Um menu abriu, parei."); return; }
        try { passo(mc); }
        catch (Throwable ex) { ex.printStackTrace(); parar(mc, "Erro: " + ex); }
    }

    static void iniciar(Minecraft mc) {
        if (mc.screen != null) return;
        ignoradas.clear();
        quedas = 0;
        try { pedir(); } catch (Throwable ex) { msg(mc, "Nao achei o mod do servidor: " + ex); return; }
        s = S.SYNC; wait = 25; t = 0;
        msg(mc, "Ligado. Lendo posicoes das esferas...");
    }

    static void parar(Minecraft mc, String texto) {
        s = S.OFF;
        soltar(mc);
        if (texto != null) msg(mc, texto);
    }

    static void passo(Minecraft mc) throws Exception {
        LocalPlayer p = mc.player;
        switch (s) {
            case SYNC -> {
                if (wait-- > 0) return;
                int pl = achaPlaneta(mc);
                if (pl < 0) {
                    if (++t < 4) { pedir(); wait = 20; return; }
                    parar(mc, "Va ate o Mundo de Historia, na Terra ou em Namek (menu ESC), e aperte K de novo.");
                    return;
                }
                planeta = (byte) pl;
                if (!bool("liberado", planeta)) { parar(mc, "Esferas bloqueadas: " + str("trava", planeta)); return; }
                escolher(mc);
            }
            case FLYON -> {
                if (wait-- > 0) return;
                if (voando(p)) { s = S.GO; ticksBola = 0; ultimaPos = p.position(); return; }
                if (++t > 3) { parar(mc, "Nao consegui ligar o voo. Ligue com F e aperte K de novo."); return; }
                alternarVoo();
                wait = 20;
            }
            case GO -> {
                ticksBola++;
                if (ticksBola > 20 * 180) { pular(mc, "demorou demais"); return; }
                if (!voando(p)) {
                    soltar(mc);
                    if (++quedas > 4) { parar(mc, "O voo desligou varias vezes (sem ki?)."); return; }
                    s = S.FLYON; t = 0; wait = 0;
                    return;
                }
                if (ticksBola % 40 == 0) pedir();
                Object cur = achar(alvoEstrelas);
                if (cur == null || coletada(cur)) { pegou(mc); return; }
                Vec3 b = centro(cur);
                double dx = b.x - p.getX(), dy = b.y - (p.getY() + 0.9), dz = b.z - p.getZ();
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d < 1.6) { soltar(mc); s = S.TOUCH; t = 0; return; }
                mirar(p, dx, dy, dz);
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(d > 10);
                if (ticksBola % 30 == 0) {
                    if (ultimaPos.distanceTo(p.position()) < 1.5 && d > 3) { s = S.UP; t = 0; }
                    ultimaPos = p.position();
                }
            }
            case UP -> {
                p.setXRot(-70f);
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(true);
                if (++t > 30) { s = S.GO; ultimaPos = p.position(); }
            }
            case TOUCH -> {
                t++;
                Object cur = achar(alvoEstrelas);
                if (cur == null || coletada(cur)) { pegou(mc); return; }
                Vec3 b = centro(cur);
                mirar(p, b.x - p.getX(), b.y - (p.getY() + 0.9), b.z - p.getZ());
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(false);
                if (t % 20 == 0) pedir();
                if (t > 100) { soltar(mc); pular(mc, "nao consegui encostar"); }
            }
            default -> {}
        }
    }

    static void pegou(Minecraft mc) throws Exception {
        soltar(mc);
        msg(mc, "Esfera de " + alvoEstrelas + " estrela(s) pega!");
        escolher(mc);
    }

    static void pular(Minecraft mc, String motivo) throws Exception {
        ignoradas.add(alvoEstrelas);
        msg(mc, "Pulando a esfera de " + alvoEstrelas + " estrela(s): " + motivo + ".");
        escolher(mc);
    }

    /** Escolhe a esfera nao coletada mais proxima. */
    static void escolher(Minecraft mc) throws Exception {
        Vec3 pos = mc.player.position();
        Object melhor = null; double md = Double.MAX_VALUE;
        for (Object e : esferas(planeta)) {
            if (coletada(e) || ignoradas.contains(inteiro(e, "estrelas"))) continue;
            double d = centro(e).distanceTo(pos);
            if (d < md) { md = d; melhor = e; }
        }
        if (melhor == null) { parar(mc, "Pronto: nao ha mais esferas para pegar neste planeta."); return; }
        alvoEstrelas = inteiro(melhor, "estrelas");
        msg(mc, "Indo para a esfera de " + alvoEstrelas + " estrela(s) (" + (int) md + " blocos).");
        s = S.FLYON; t = 0; wait = 0;
    }

    // ---------------- util ----------------
    static void mirar(LocalPlayer p, double dx, double dy, double dz) {
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot((float) -Math.toDegrees(Math.atan2(dy, h)));
    }

    static void soltar(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    static void msg(Minecraft mc, String t) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Esferas] " + t), false);
    }

    static Vec3 centro(Object e) throws Exception {
        return new Vec3(inteiro(e, "x") + 0.5, inteiro(e, "y") + 0.5, inteiro(e, "z") + 0.5);
    }

    static Object achar(int estrelas) throws Exception {
        for (Object e : esferas(planeta)) if (inteiro(e, "estrelas") == estrelas) return e;
        return null;
    }

    static int achaPlaneta(Minecraft mc) throws Exception {
        String dim = mc.level.dimension().location().toString();
        for (byte b = 0; b <= 1; b++)
            if (bool("conhecido", b) && dim.equals(str("dimensao", b))) return b;
        return -1;
    }

    // ---------------- reflexao: mod do servidor ----------------
    static Class<?> classe(String nome) throws Exception {
        return Class.forName(nome, true, Minecraft.class.getClassLoader());
    }

    static void pedir() throws Exception { classe(EC).getMethod("pedir").invoke(null); }

    static List<?> esferas(byte pl) throws Exception { return (List<?>) classe(EC).getMethod("esferas", byte.class).invoke(null, pl); }

    static boolean bool(String m, byte pl) throws Exception { return (Boolean) classe(EC).getMethod(m, byte.class).invoke(null, pl); }

    static String str(String m, byte pl) throws Exception { return String.valueOf(classe(EC).getMethod(m, byte.class).invoke(null, pl)); }

    static int inteiro(Object rec, String m) throws Exception { return ((Number) rec.getClass().getMethod(m).invoke(rec)).intValue(); }

    static boolean coletada(Object rec) throws Exception { return (Boolean) rec.getClass().getMethod("coletada").invoke(rec); }

    // ---------------- reflexao: voo do DragonMineZ ----------------
    static void alternarVoo() throws Exception { classe(FLY).getMethod("toggleFlightFromMenu").invoke(null); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean voando(LocalPlayer p) {
        try {
            Object cap = classe(CAP).getField("INSTANCE").get(null);
            Method get = classe(PROV).getMethod("get", Capability.class, Entity.class);
            LazyOptional<?> lo = (LazyOptional<?>) get.invoke(null, cap, p);
            Optional<?> stats = lo.resolve();
            if (stats.isEmpty()) return false;
            Object st = stats.get();
            Object skills = st.getClass().getMethod("getSkills").invoke(st);
            Object skill = skills.getClass().getMethod("getSkill", String.class).invoke(skills, "fly");
            return skill != null && (Boolean) skill.getClass().getMethod("isActive").invoke(skill);
        } catch (Throwable ex) {
            return !p.onGround(); // plano B: se esta no ar, considera voando
        }
    }
}
