# Shizuku Next attribution

Startup recovery includes adaptations of the ADB service-command transport,
TCP-before-starter ordering, same-boot endpoint reuse, retry backoff and
watchdog failure-handling concepts from Shizuku Next.

Project https://github.com/rushiranpise/Shizuku-Next
Release v14.0.33-next
Source revision 8508e1d50a293cec82eb60465bcc17022d7a5926
License Apache-2.0, included alongside this notice.

Relevant upstream files include manager/src/main/java/moe/shizuku/manager/adb/AdbClient.kt,
adb/AdbStarter.kt, start/StartTransport.kt, service/StartCircuitBreaker.kt and
worker/AdbStartWorker.kt. The embed implementation retains the existing manager
and uses its own isolated foreground service and Android JobScheduler backstop.
It retains the nested Shizuku API revision recorded by the embedding repository.

Experimental offline startup additionally adapts ForcedWirelessDebugging.kt from
revision 35d46fb8dbada23960a9818fa957c8c3f283124a. The embed implementation adds
cancellation-safe hotspot ownership, bounded automatic attempts, runtime
permission consent and a local diagnostics view. Its API submodule is unchanged.
