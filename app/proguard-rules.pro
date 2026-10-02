# R8 rules for the release build.
#
# ⛔ A GREEN `assembleDebug` IS NO EVIDENCE THAT ANY OF THIS IS CORRECT. Debug builds do not
# minify, so a missing rule here is completely invisible locally and surfaces as a crash in a
# Play track. `./gradlew :app:assembleRelease` is the minimum check, and it only proves the
# build SUCCEEDS — it does not prove the app runs. Install the artifact and open a screen that
# decodes an API response before believing minification is safe.
#
# ⚠️ To verify: assembleRelease succeeds and the `$$serializer` classes survive into
# `app/build/outputs/mapping/release/mapping.txt` (scripts/verify-release-minification.sh).

# Keep line numbers so Sentry stack traces from release builds are readable, and hide the
# original source file name.
#
# ⚠️ THESE TWO LINES PREDATE SENTRY AND ARE NOW ALSO SHIPPED BY IT, WHICH IS A DUPLICATE WORTH
# KEEPING RATHER THAN REMOVING. `sentry-android-core`'s consumer rules carry
# `-keepattributes LineNumberTable,SourceFile` themselves (read out of proguard.txt inside
# sentry-android-core-8.53.0.aar, not assumed), so R8 would keep the attribute even if this line
# went. It stays because it is the LOCAL statement of intent: release stack traces have to be
# readable whether or not a crash reporter happens to be a dependency, and deleting a rule on the
# grounds that a third-party AAR currently supplies it makes readable stack traces a property of
# the dependency graph. `-renamesourcefileattribute` is ours alone and Sentry ships no equivalent.
#
# ⛔ AND `verify-release-minification.sh` GREPS THIS FILE FOR THE LITERAL STRING
# `SourceFile,LineNumberTable`, so the ORDER of the two names here is load-bearing. Sentry's copy
# spells them the other way round (`LineNumberTable,SourceFile`), which is identical to R8 and
# invisible to that grep — reordering these words would make the gate report that line numbers are
# not kept while the build still keeps them.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── kotlinx.serialization ─────────────────────────────────────────────────────
#
# ⚠️ MOSTLY DEFENSIVE, AND WORTH UNDERSTANDING WHY. This app calls `Foo.serializer()`
# EXPLICITLY everywhere (see HttpDistrictApi), which the compiler plugin resolves statically —
# so R8 already keeps what it needs and the build works without these rules. They exist to
# cover the reflective path: `Json.decodeFromString<Foo>(...)` without an explicit serializer
# looks up `Foo$$serializer` through the `Companion`, and R8 cannot see that edge. One
# reflective call added later, anywhere, would otherwise fail at runtime in release only.
#
# ⛔ Do NOT "clean these up" on the grounds that the build passes without them. A build that
# passes without them only proves no reflective call exists yet.

# Keep the generated serializer for anything annotated @Serializable, and the Companion that
# exposes it.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ⚠️ The serialization runtime reads @Serializable/@SerialName off classes in some paths, so the
# annotations themselves must survive. This is what keeps `@SerialName("sms")` on
# CallFollowUp working — the wire key differs from the property name, so losing it would rename
# the field silently rather than failing loudly.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# ── OkHttp ────────────────────────────────────────────────────────────────────
#
# ⚠️ OkHttp ships its own consumer rules, so nothing is needed for normal use. These two silence
# warnings about optional compile-time-only dependencies it references but does not require:
# Conscrypt/BouncyCastle providers and Animal Sniffer annotations. Without them R8 fails the
# build on missing classes that are genuinely absent by design.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.*

# ── Coroutines ────────────────────────────────────────────────────────────────
#
# ⚠️ The debug agent and the ServiceLoader-based main-dispatcher factory are the two things R8
# cannot trace. The dispatcher factory matters here: losing it breaks Dispatchers.Main, which
# every ViewModel in this app launches on.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.debug.**

# ── LiveKit ───────────────────────────────────────────────────────────────────
#
# ⚠️ NOTHING IS NEEDED HERE, AND THE REASON IS WORTH RECORDING RATHER THAN LEAVING TO LOOK
# LIKE AN OMISSION. `io.livekit:livekit-android` ships CONSUMER rules inside its own AAR
# (`proguard.txt`), which AGP merges into the R8 configuration automatically. They cover the
# three things R8 cannot trace for it: its `@Serializable` protocol classes, the WebRTC JNI
# surface in both directions (`native <methods>` and anything annotated `@CalledByNative`), and
# the reflective SDP parser registrations in `android.gov.nist.javax.sdp.parser`. Duplicating
# them here would create a second copy that a version bump silently desynchronises.
#
# ⛔ THE COMMENT THIS REPLACES SAID "NOTHING FOR ROOM OR LiveKit YET, BECAUSE NEITHER IS USED",
# and half of it stopped being true when `:core:core-media` reached `:app`. That is the shape to
# distrust: a note asserting an absence, with nothing checking it. Room is still genuinely
# deferred (the API has no stable pagination key for a RemoteMediator).

# ── Sentry ────────────────────────────────────────────────────────────────────
#
# ⚠️ NOTHING IS NEEDED HERE EITHER, FOR EXACTLY THE REASON THE LiveKit SECTION ABOVE GIVES, AND
# THE CHECK WAS THE SAME: `io.sentry:sentry-android-core` ships CONSUMER rules in its own AAR
# (`proguard.txt`), which AGP merges automatically. Confirmed by extracting
# sentry-android-core-8.53.0.aar and reading that file rather than by reasoning about it. They
# cover the four things R8 cannot trace for the SDK: `-keep class * extends io.sentry.SentryOptions`
# (option classes are instantiated reflectively, and their METHOD NAMES are called from native over
# JNI), `-keepnames class * implements io.sentry.Integration` (integration class names appear in
# the event payload, so obfuscating them makes the report say `a.b.c`),
# `-keepclassmembers enum io.sentry.**`, and `-keeppackagenames io.sentry.**` plus
# `-keep,allowshrinking,allowobfuscation class * extends java.lang.Throwable` so retraced frames
# and runtime exception identities cannot disagree after horizontal class merging.
#
# ⛔ DO NOT COPY THEM HERE "TO BE SAFE". A second copy is a second thing to desynchronise on an SDK
# bump, which is the same trap this file already records for LiveKit — and the Sentry set includes
# `-dontwarn` lines for optional integrations (Timber, Fragment, Compose, Apollo, OkHttp,
# navigation, replay, spotlight) whose correctness is tied to the exact SDK version.
#
# ⚠️ THE ONE PIECE OF SENTRY PLUMBING THAT IS **NOT** A KEEP RULE, so that it is not looked for
# here: the mapping file's identity. `io.sentry.android.gradle` generates a UUID per release build,
# writes it into `assets/sentry-debug-meta.properties`, and uploads the mapping under the same
# UUID — see the `sentry { }` block in app/build.gradle.kts. R8 rules have nothing to do with it,
# and no rule in this file can compensate for that upload not having happened.

# ── Declared-but-not-yet-wired endpoints ──────────────────────────────────────
#
# UnreadCountResponse: DistrictApi declares the unread-count endpoint and HttpDistrictApi decodes
# it with an explicit serializer, but no ViewModel calls it yet — InboxViewModel deliberately sums
# per-thread unreadCount from the conversations page it already fetched rather than spend a second
# round trip. R8 therefore strips the whole chain as unreachable, and verify-release-minification
# fails the build because the DTO is still referenced in shipped sources. Keep the generated
# serializer until a caller (the nav-bar badge is the expected one) wires the endpoint up; if the
# endpoint is removed instead, delete this rule in the same change.
-keep class com.distronode.districtai.core.model.UnreadCountResponse$$serializer { *; }
