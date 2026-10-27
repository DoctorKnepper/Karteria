package com.karteria.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.health.connect.AggregateRecordsGroupedByPeriodResponse;
import android.health.connect.AggregateRecordsRequest;
import android.health.connect.AggregateRecordsResponse;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.LocalTimeRangeFilter;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.ReadRecordsRequestUsingIds;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.datatypes.ActiveCaloriesBurnedRecord;
import android.health.connect.datatypes.AggregationType;
import android.health.connect.datatypes.BloodPressureRecord;
import android.health.connect.datatypes.BodyFatRecord;
import android.health.connect.datatypes.Device;
import android.health.connect.datatypes.DistanceRecord;
import android.health.connect.datatypes.ElevationGainedRecord;
import android.health.connect.datatypes.ExerciseLap;
import android.health.connect.datatypes.ExerciseRoute;
import android.health.connect.datatypes.ExerciseSegment;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.FloorsClimbedRecord;
import android.health.connect.datatypes.HeartRateRecord;
import android.health.connect.datatypes.HeartRateVariabilityRmssdRecord;
import android.health.connect.datatypes.InstantRecord;
import android.health.connect.datatypes.OxygenSaturationRecord;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.RespiratoryRateRecord;
import android.health.connect.datatypes.RestingHeartRateRecord;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.SpeedRecord;
import android.health.connect.datatypes.StepsRecord;
import android.health.connect.datatypes.TotalCaloriesBurnedRecord;
import android.health.connect.datatypes.Vo2MaxRecord;
import android.health.connect.datatypes.WeightRecord;
import android.health.connect.datatypes.units.Energy;
import android.health.connect.datatypes.units.Length;
import android.health.connect.datatypes.units.Mass;
import android.health.connect.datatypes.units.Velocity;
import android.os.Build;
import android.os.OutcomeReceiver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Reads the user's data from Health Connect (built into Android 14 and newer) and hands it to the
 * page as JSON. Read-only: Karteria never writes to Health Connect. Everything stays on the phone.
 */
final class Health {
    interface Reply { void send(int id, String json); void progress(int id, String step); }

    static final String P = "android.permission.health.";
    /** Short names; the full permission is P + name. */
    static final String[] PERMS = {
            "READ_EXERCISE", "READ_EXERCISE_ROUTES", "READ_HEART_RATE", "READ_STEPS", "READ_DISTANCE",
            "READ_ACTIVE_CALORIES_BURNED", "READ_TOTAL_CALORIES_BURNED", "READ_FLOORS_CLIMBED",
            "READ_ELEVATION_GAINED", "READ_SPEED", "READ_WEIGHT", "READ_BODY_FAT", "READ_RESTING_HEART_RATE",
            "READ_HEART_RATE_VARIABILITY", "READ_OXYGEN_SATURATION", "READ_RESPIRATORY_RATE",
            "READ_BLOOD_PRESSURE", "READ_VO2_MAX", "READ_SLEEP", "READ_HEALTH_DATA_HISTORY"
    };

    private static final Executor DIRECT = Runnable::run;
    private final Context ctx;
    private final Reply reply;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    Health(Context ctx, Reply reply) { this.ctx = ctx; this.reply = reply; }

    static boolean supported() { return Build.VERSION.SDK_INT >= 34; }

    static String[] fullPerms() {
        String[] out = new String[PERMS.length];
        for (int i = 0; i < PERMS.length; i++) out[i] = P + PERMS[i];
        return out;
    }

    boolean has(String shortName) {
        return ctx.checkSelfPermission(P + shortName) == PackageManager.PERMISSION_GRANTED;
    }

    String granted() {
        JSONArray a = new JSONArray();
        if (supported()) for (String p : PERMS) if (has(p)) a.put(p);
        return a.toString();
    }

    private HealthConnectManager hcm() { return ctx.getSystemService(HealthConnectManager.class); }

    /* ---------- plumbing ---------- */

    interface Call<R> { void run(OutcomeReceiver<R, HealthConnectException> r); }

    @SuppressWarnings("unchecked")
    private static <R> R await(Call<R> call) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] box = new Object[2];
        call.run(new OutcomeReceiver<R, HealthConnectException>() {
            @Override public void onResult(R r) { box[0] = r; latch.countDown(); }
            @Override public void onError(HealthConnectException e) { box[1] = e; latch.countDown(); }
        });
        if (!latch.await(45, TimeUnit.SECONDS)) throw new Exception("Health Connect did not respond");
        if (box[1] != null) throw (Exception) box[1];
        return (R) box[0];
    }

    private <T extends Record> List<T> readAll(Class<T> cls, Instant from, Instant to) throws Exception {
        List<T> out = new ArrayList<>();
        long token = -1;
        int guard = 0;
        do {
            ReadRecordsRequestUsingFilters.Builder<T> b = new ReadRecordsRequestUsingFilters.Builder<>(cls)
                    .setTimeRangeFilter(new TimeInstantRangeFilter.Builder().setStartTime(from).setEndTime(to).build())
                    .setPageSize(5000);
            if (token != -1) b.setPageToken(token);
            final ReadRecordsRequestUsingFilters<T> req = b.build();
            ReadRecordsResponse<T> res = await(r -> hcm().readRecords(req, DIRECT, r));
            out.addAll(res.getRecords());
            token = res.getNextPageToken();
        } while (token != -1 && ++guard < 400);
        return out;
    }

    private void run(final int id, final Job job) {
        worker.execute(() -> {
            String json;
            try {
                json = job.run().toString();
            } catch (Throwable e) {
                JSONObject err = new JSONObject();
                try {
                    String msg = String.valueOf(e.getMessage());
                    if (e instanceof HealthConnectException
                            && ((HealthConnectException) e).getErrorCode() == HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED)
                        msg = "rate";
                    err.put("error", msg);
                } catch (Exception ignored) { }
                json = err.toString();
            }
            reply.send(id, json);
        });
    }

    interface Job { Object run() throws Exception; }

    private static String dayOf(Instant t, ZoneOffset off) {
        return (off != null ? t.atOffset(off).toLocalDate() : t.atZone(ZoneId.systemDefault()).toLocalDate()).toString();
    }

    private static double num(Object v) {
        if (v == null) return Double.NaN;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Double) return (Double) v;
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Length) return ((Length) v).getInMeters();
        if (v instanceof Energy) return ((Energy) v).getInCalories() / 1000.0;   // kcal
        if (v instanceof Mass) return ((Mass) v).getInGrams() / 1000.0;          // kg
        if (v instanceof Velocity) return ((Velocity) v).getInMetersPerSecond();
        return Double.NaN;
    }

    private static void putNum(JSONObject o, String k, double v, int digits) throws Exception {
        if (Double.isNaN(v) || Double.isInfinite(v)) return;
        double f = Math.pow(10, digits);
        o.put(k, Math.round(v * f) / f);
    }

    /* ---------- sync: daily totals, workouts, body & vitals, sleep ---------- */

    void sync(int id, long fromMs, long toMs) {
        run(id, () -> {
            Instant from = Instant.ofEpochMilli(fromMs), to = Instant.ofEpochMilli(toMs);
            JSONObject out = new JSONObject();
            out.put("from", fromMs);
            out.put("to", toMs);
            JSONArray errors = new JSONArray();
            // each kind of data is read on its own, so one slow or failing type doesn't block the rest
            try { reply.progress(id, "workouts"); if (has("READ_EXERCISE")) out.put("workouts", workouts(from, to)); }
            catch (Exception e) { errors.put("workouts: " + why(e)); }
            try { reply.progress(id, "daily activity"); out.put("days", dailyTotals(id, from, to)); }
            catch (Exception e) { errors.put("activity: " + why(e)); }
            try { reply.progress(id, "body measurements"); out.put("vitals", vitals(from, to)); }
            catch (Exception e) { errors.put("body: " + why(e)); }
            try { reply.progress(id, "sleep"); if (has("READ_SLEEP")) out.put("sleep", sleep(from, to)); }
            catch (Exception e) { errors.put("sleep: " + why(e)); }
            if (errors.length() > 0) out.put("errors", errors);
            // heart-rate trends crunch every sample the watch recorded, so they come last and a slow or
            // failed read only leaves a warning instead of holding up everything else
            if (has("READ_HEART_RATE")) {
                try { out.put("heart", heart(id, from, to)); }
                catch (Exception e) { out.put("warnings", new JSONArray().put("heart rate: " + why(e))); }
            }
            return out;
        });
    }

    private static String why(Exception e) {
        if (e instanceof HealthConnectException
                && ((HealthConnectException) e).getErrorCode() == HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED) return "rate";
        return String.valueOf(e.getMessage());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private JSONArray dailyTotals(int id, Instant from, Instant to) throws Exception {
        Map<String, AggregationType> types = new LinkedHashMap<>();
        if (has("READ_STEPS")) types.put("steps", StepsRecord.STEPS_COUNT_TOTAL);
        if (has("READ_DISTANCE")) types.put("dist", DistanceRecord.DISTANCE_TOTAL);
        if (has("READ_ACTIVE_CALORIES_BURNED")) types.put("acal", ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL);
        if (has("READ_TOTAL_CALORIES_BURNED")) types.put("tcal", TotalCaloriesBurnedRecord.ENERGY_TOTAL);
        if (has("READ_FLOORS_CLIMBED")) types.put("floors", FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL);
        if (has("READ_EXERCISE")) types.put("exmin", ExerciseSessionRecord.EXERCISE_DURATION_TOTAL);
        JSONArray days = new JSONArray();
        if (types.isEmpty()) return days;
        ZoneId zone = ZoneId.systemDefault();
        LocalDate start = from.atZone(zone).toLocalDate(), end = to.atZone(zone).toLocalDate().plusDays(1);
        for (LocalDate a = start; a.isBefore(end); a = a.plusDays(31)) {
            LocalDate z = a.plusDays(31).isBefore(end) ? a.plusDays(31) : end;
            reply.progress(id, "daily activity, " + a.getMonth().toString().substring(0, 1) + a.getMonth().toString().substring(1, 3).toLowerCase() + " " + a.getYear());
            AggregateRecordsRequest.Builder b = new AggregateRecordsRequest.Builder(new LocalTimeRangeFilter.Builder()
                    .setStartTime(a.atStartOfDay()).setEndTime(z.atStartOfDay()).build());
            for (AggregationType t : types.values()) b.addAggregationType(t);
            final AggregateRecordsRequest req = b.build();
            List<AggregateRecordsGroupedByPeriodResponse> res =
                    (List<AggregateRecordsGroupedByPeriodResponse>) (List) await(r -> hcm().aggregateGroupByPeriod(req, Period.ofDays(1), DIRECT, (OutcomeReceiver) r));
            for (AggregateRecordsGroupedByPeriodResponse g : res) {
                JSONObject d = new JSONObject();
                boolean any = false;
                for (Map.Entry<String, AggregationType> e : types.entrySet()) {
                    Object v = g.get(e.getValue());
                    double n = num(v);
                    if (Double.isNaN(n)) continue;
                    if (e.getKey().equals("exmin")) n = n / 60000.0;   // ms → minutes
                    putNum(d, e.getKey(), n, e.getKey().equals("dist") ? 1 : 0);
                    any = true;
                }
                if (!any) continue;
                d.put("d", g.getStartTime().toLocalDate().toString());
                days.put(d);
            }
        }
        return days;
    }

    private JSONArray workouts(Instant from, Instant to) throws Exception {
        JSONArray arr = new JSONArray();
        for (ExerciseSessionRecord s : readAll(ExerciseSessionRecord.class, from, to)) {
            JSONObject o = new JSONObject();
            o.put("id", s.getMetadata().getId());
            o.put("t", s.getExerciseType());
            if (s.getTitle() != null && s.getTitle().length() > 0) o.put("ti", s.getTitle().toString());
            if (s.getNotes() != null && s.getNotes().length() > 0) o.put("no", s.getNotes().toString());
            o.put("s", s.getStartTime().toEpochMilli());
            o.put("e", s.getEndTime().toEpochMilli());
            o.put("d", dayOf(s.getStartTime(), s.getStartZoneOffset()));
            o.put("src", s.getMetadata().getDataOrigin().getPackageName());
            Device dev = s.getMetadata().getDevice();
            if (dev != null) {
                String m = ((dev.getManufacturer() == null ? "" : dev.getManufacturer()) + " "
                        + (dev.getModel() == null ? "" : dev.getModel())).trim();
                if (!m.isEmpty()) o.put("dev", m);
            }
            if (s.hasRoute()) o.put("r", 1);
            if (!s.getLaps().isEmpty()) o.put("laps", s.getLaps().size());
            arr.put(o);
        }
        return arr;
    }

    private interface Val<T> { double of(T r); }

    private <T extends InstantRecord> void daily(JSONObject out, String key, Class<T> cls, Instant from, Instant to,
                                                 Val<T> val, int digits) throws Exception {
        TreeMap<String, double[]> m = new TreeMap<>();   // sum, n, min, max
        for (T r : readAll(cls, from, to)) {
            double v = val.of(r);
            if (Double.isNaN(v)) continue;
            String k = dayOf(r.getTime(), r.getZoneOffset());
            double[] a = m.get(k);
            if (a == null) m.put(k, new double[]{v, 1, v, v});
            else { a[0] += v; a[1]++; a[2] = Math.min(a[2], v); a[3] = Math.max(a[3], v); }
        }
        if (m.isEmpty()) return;
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, double[]> e : m.entrySet()) {
            double[] a = e.getValue();
            JSONArray row = new JSONArray();
            row.put(e.getKey());
            row.put(Math.round(a[0] / a[1] * Math.pow(10, digits)) / Math.pow(10, digits));
            if (a[1] > 1) { row.put(Math.round(a[2] * Math.pow(10, digits)) / Math.pow(10, digits)); row.put(Math.round(a[3] * Math.pow(10, digits)) / Math.pow(10, digits)); }
            arr.put(row);
        }
        out.put(key, arr);
    }

    private JSONObject vitals(Instant from, Instant to) throws Exception {
        JSONObject v = new JSONObject();
        if (has("READ_WEIGHT")) daily(v, "weight", WeightRecord.class, from, to, r -> r.getWeight().getInGrams() / 1000.0, 2);
        if (has("READ_BODY_FAT")) daily(v, "fat", BodyFatRecord.class, from, to, r -> r.getPercentage().getValue(), 1);
        if (has("READ_RESTING_HEART_RATE")) daily(v, "rhr", RestingHeartRateRecord.class, from, to, r -> r.getBeatsPerMinute(), 0);
        if (has("READ_HEART_RATE_VARIABILITY")) daily(v, "hrv", HeartRateVariabilityRmssdRecord.class, from, to, r -> r.getHeartRateVariabilityMillis(), 1);
        if (has("READ_OXYGEN_SATURATION")) daily(v, "spo2", OxygenSaturationRecord.class, from, to, r -> r.getPercentage().getValue(), 1);
        if (has("READ_RESPIRATORY_RATE")) daily(v, "resp", RespiratoryRateRecord.class, from, to, r -> r.getRate(), 1);
        if (has("READ_VO2_MAX")) daily(v, "vo2", Vo2MaxRecord.class, from, to, r -> r.getVo2MillilitersPerMinuteKilogram(), 1);
        if (has("READ_BLOOD_PRESSURE")) {
            daily(v, "bps", BloodPressureRecord.class, from, to, r -> r.getSystolic().getInMillimetersOfMercury(), 0);
            daily(v, "bpd", BloodPressureRecord.class, from, to, r -> r.getDiastolic().getInMillimetersOfMercury(), 0);
        }
        return v;
    }

    /** Daily average / lowest / highest heart rate, and average heart rate while asleep each night. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private JSONObject heart(int id, Instant from, Instant to) throws Exception {
        JSONArray avg = new JSONArray(), lo = new JSONArray(), hi = new JSONArray(), slp = new JSONArray();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate start = from.atZone(zone).toLocalDate(), end = to.atZone(zone).toLocalDate().plusDays(1);
        for (LocalDate a = start; a.isBefore(end); a = a.plusDays(15)) {
            LocalDate z = a.plusDays(15).isBefore(end) ? a.plusDays(15) : end;
            reply.progress(id, "heart rate, " + a.getMonth().toString().substring(0, 1) + a.getMonth().toString().substring(1, 3).toLowerCase() + " " + a.getDayOfMonth());
            AggregateRecordsRequest.Builder b = new AggregateRecordsRequest.Builder(new LocalTimeRangeFilter.Builder()
                    .setStartTime(a.atStartOfDay()).setEndTime(z.atStartOfDay()).build());
            b.addAggregationType(HeartRateRecord.BPM_AVG);
            b.addAggregationType(HeartRateRecord.BPM_MIN);
            b.addAggregationType(HeartRateRecord.BPM_MAX);
            final AggregateRecordsRequest req = b.build();
            List<AggregateRecordsGroupedByPeriodResponse> res =
                    (List<AggregateRecordsGroupedByPeriodResponse>) (List) await(r -> hcm().aggregateGroupByPeriod(req, Period.ofDays(1), DIRECT, (OutcomeReceiver) r));
            for (AggregateRecordsGroupedByPeriodResponse g : res) {
                double v = num(g.get(HeartRateRecord.BPM_AVG));
                if (Double.isNaN(v)) continue;
                String d = g.getStartTime().toLocalDate().toString();
                avg.put(new JSONArray().put(d).put(Math.round(v)));
                double mn = num(g.get(HeartRateRecord.BPM_MIN)), mx = num(g.get(HeartRateRecord.BPM_MAX));
                if (!Double.isNaN(mn)) lo.put(new JSONArray().put(d).put(Math.round(mn)));
                if (!Double.isNaN(mx)) hi.put(new JSONArray().put(d).put(Math.round(mx)));
            }
        }
        if (has("READ_SLEEP")) {
            reply.progress(id, "sleeping heart rate");
            for (SleepSessionRecord s : readAll(SleepSessionRecord.class, from, to)) {
                if (s.getEndTime().getEpochSecond() - s.getStartTime().getEpochSecond() < 3 * 3600) continue;   // skip naps
                final AggregateRecordsRequest req = new AggregateRecordsRequest.Builder<Long>(new TimeInstantRangeFilter.Builder()
                        .setStartTime(s.getStartTime()).setEndTime(s.getEndTime()).build())
                        .addAggregationType(HeartRateRecord.BPM_AVG).build();
                AggregateRecordsResponse<Long> r2 = await(r -> hcm().aggregate(req, DIRECT, r));
                Long v = r2.get(HeartRateRecord.BPM_AVG);
                if (v != null) slp.put(new JSONArray().put(dayOf(s.getEndTime(), s.getEndZoneOffset())).put(v));
            }
        }
        JSONObject o = new JSONObject();
        o.put("hravg", avg); o.put("hrlo", lo); o.put("hrhi", hi);
        if (has("READ_SLEEP")) o.put("hrsleep", slp);
        return o;
    }

    private JSONArray sleep(Instant from, Instant to) throws Exception {
        JSONArray arr = new JSONArray();
        for (SleepSessionRecord s : readAll(SleepSessionRecord.class, from, to)) {
            JSONObject o = new JSONObject();
            long s0 = s.getStartTime().getEpochSecond();
            o.put("id", s.getMetadata().getId());
            o.put("s", s.getStartTime().toEpochMilli());
            o.put("e", s.getEndTime().toEpochMilli());
            o.put("d", dayOf(s.getEndTime(), s.getEndZoneOffset()));   // the morning you woke up
            o.put("src", s.getMetadata().getDataOrigin().getPackageName());
            JSONArray st = new JSONArray();
            for (SleepSessionRecord.Stage g : s.getStages()) {
                JSONArray row = new JSONArray();
                row.put(g.getType());
                row.put(g.getStartTime().getEpochSecond() - s0);
                row.put(g.getEndTime().getEpochSecond() - g.getStartTime().getEpochSecond());
                st.put(row);
            }
            o.put("st", st);
            arr.put(o);
        }
        return arr;
    }

    /* ---------- per-workout totals (distance, calories, heart rate…) ---------- */

    @SuppressWarnings({"rawtypes", "unchecked"})
    void workoutStats(int id, String sessionsJson) {
        run(id, () -> {
            JSONArray in = new JSONArray(sessionsJson);
            JSONObject out = new JSONObject();
            Map<String, AggregationType> types = new LinkedHashMap<>();
            if (has("READ_DISTANCE")) types.put("dist", DistanceRecord.DISTANCE_TOTAL);
            if (has("READ_ACTIVE_CALORIES_BURNED")) types.put("acal", ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL);
            if (has("READ_TOTAL_CALORIES_BURNED")) types.put("tcal", TotalCaloriesBurnedRecord.ENERGY_TOTAL);
            if (has("READ_STEPS")) types.put("steps", StepsRecord.STEPS_COUNT_TOTAL);
            if (has("READ_ELEVATION_GAINED")) types.put("elev", ElevationGainedRecord.ELEVATION_GAINED_TOTAL);
            if (has("READ_HEART_RATE")) {
                types.put("hra", HeartRateRecord.BPM_AVG);
                types.put("hrx", HeartRateRecord.BPM_MAX);
                types.put("hrn", HeartRateRecord.BPM_MIN);
            }
            if (has("READ_SPEED")) types.put("spx", SpeedRecord.SPEED_MAX);
            for (int i = 0; i < in.length(); i++) {
                JSONObject s = in.getJSONObject(i);
                JSONObject st = new JSONObject();
                if (!types.isEmpty()) {
                    AggregateRecordsRequest.Builder b = new AggregateRecordsRequest.Builder(new TimeInstantRangeFilter.Builder()
                            .setStartTime(Instant.ofEpochMilli(s.getLong("s"))).setEndTime(Instant.ofEpochMilli(s.getLong("e"))).build());
                    for (AggregationType t : types.values()) b.addAggregationType(t);
                    final AggregateRecordsRequest req = b.build();
                    AggregateRecordsResponse res = (AggregateRecordsResponse) await(r -> hcm().aggregate(req, DIRECT, (OutcomeReceiver) r));
                    for (Map.Entry<String, AggregationType> e : types.entrySet())
                        putNum(st, e.getKey(), num(res.get(e.getValue())), e.getKey().equals("dist") || e.getKey().equals("elev") || e.getKey().equals("spx") ? 2 : 0);
                }
                out.put(s.getString("id"), st);
            }
            return out;
        });
    }

    /* ---------- one workout in full: heart rate, speed, distance over time, route, laps ---------- */

    void workoutDetail(int id, String sessionId, long startMs, long endMs) {
        run(id, () -> {
            Instant a = Instant.ofEpochMilli(startMs), b = Instant.ofEpochMilli(endMs);
            long s0 = a.getEpochSecond();
            JSONObject o = new JSONObject();
            if (has("READ_HEART_RATE")) {
                JSONArray hr = new JSONArray();
                for (HeartRateRecord r : readAll(HeartRateRecord.class, a, b))
                    for (HeartRateRecord.HeartRateSample x : r.getSamples()) {
                        if (x.getTime().isBefore(a) || x.getTime().isAfter(b)) continue;
                        hr.put(new JSONArray().put(x.getTime().getEpochSecond() - s0).put(x.getBeatsPerMinute()));
                    }
                o.put("hr", hr);
            }
            if (has("READ_SPEED")) {
                JSONArray sp = new JSONArray();
                for (SpeedRecord r : readAll(SpeedRecord.class, a, b))
                    for (SpeedRecord.SpeedRecordSample x : r.getSamples())
                        sp.put(new JSONArray().put(x.getTime().getEpochSecond() - s0).put(Math.round(x.getSpeed().getInMetersPerSecond() * 100) / 100.0));
                o.put("speed", sp);
            }
            if (has("READ_DISTANCE")) {
                JSONArray di = new JSONArray();
                for (DistanceRecord r : readAll(DistanceRecord.class, a, b))
                    di.put(new JSONArray().put(r.getStartTime().getEpochSecond() - s0).put(r.getEndTime().getEpochSecond() - s0)
                            .put(Math.round(r.getDistance().getInMeters() * 10) / 10.0).put(r.getMetadata().getDataOrigin().getPackageName()));
                o.put("dist", di);
            }
            if (has("READ_EXERCISE")) {
                final ReadRecordsRequestUsingIds<ExerciseSessionRecord> req =
                        new ReadRecordsRequestUsingIds.Builder<>(ExerciseSessionRecord.class).addId(sessionId).build();
                ReadRecordsResponse<ExerciseSessionRecord> res = await(r -> hcm().readRecords(req, DIRECT, r));
                if (!res.getRecords().isEmpty()) {
                    ExerciseSessionRecord s = res.getRecords().get(0);
                    JSONArray laps = new JSONArray();
                    for (ExerciseLap l : s.getLaps())
                        laps.put(new JSONArray().put(l.getStartTime().getEpochSecond() - s0).put(l.getEndTime().getEpochSecond() - s0)
                                .put(l.getLength() == null ? 0 : Math.round(l.getLength().getInMeters() * 10) / 10.0));
                    o.put("laps", laps);
                    JSONArray seg = new JSONArray();
                    for (ExerciseSegment g : s.getSegments())
                        seg.put(new JSONArray().put(g.getStartTime().getEpochSecond() - s0).put(g.getEndTime().getEpochSecond() - s0)
                                .put(g.getSegmentType()).put(g.getRepetitionsCount()));
                    o.put("seg", seg);
                    ExerciseRoute route = null;
                    try { route = s.getRoute(); } catch (Exception ignored) { }
                    if (route != null) o.put("route", routeJson(route, s0));
                    else if (s.hasRoute()) o.put("routeStatus", "consent");
                }
            }
            return o;
        });
    }

    static JSONArray routeJson(ExerciseRoute route, long s0) throws Exception {
        JSONArray arr = new JSONArray();
        for (ExerciseRoute.Location l : route.getRouteLocations()) {
            JSONArray p = new JSONArray().put(l.getTime().getEpochSecond() - s0)
                    .put(Math.round(l.getLatitude() * 1e6) / 1e6).put(Math.round(l.getLongitude() * 1e6) / 1e6);
            if (l.getAltitude() != null) p.put(Math.round(l.getAltitude().getInMeters() * 10) / 10.0);
            arr.put(p);
        }
        return arr;
    }

    /** The route the user just allowed for one workout (Android 14 asks per workout). */
    static String routeFromResult(android.content.Intent data, long startMs) {
        try {
            if (data == null) return null;
            ExerciseRoute r = data.getParcelableExtra(HealthConnectManager.EXTRA_EXERCISE_ROUTE, ExerciseRoute.class);
            return r == null ? null : routeJson(r, startMs / 1000).toString();
        } catch (Exception e) { return null; }
    }

    void shutdown() { worker.shutdownNow(); }
}
