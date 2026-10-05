# MetalMC — Conversation Summary

Date: October 3, 2026

## What was discussed

The user asked whether the assistant could make a Minecraft mod that renders directly through Apple's Metal API instead of OpenGL or Vulkan. The discussion concerned Minecraft Java Edition on macOS.

The assistant said a native Metal renderer is technically feasible, but replacing the complete renderer reliably would require substantial development and testing. A possible approach would connect a Java mod to a native Objective-C++ Metal library and implement terrain, entities, particles, lighting, and the interface in stages. Shader-pack support and compatibility with other rendering mods would require additional work. Performance improvements would need to be measured.

Existing projects were mentioned as references, including [MetalMC](https://github.com/XaviFortes/MetalMC), [MetalRender](https://github.com/webblepebbles/MetalRender), and [Metallum](https://github.com/kokodio/metallum). These references are separate projects; this Desktop folder is only a record of this conversation. Their features and compatibility were not tested locally.

## Mac specifications checked locally

- Model: MacBook Air
- Model identifier: Mac17,3
- Chip: Apple M5
- CPU: 10 cores
- GPU: 8 cores
- Unified memory: 16 GB
- macOS: 27.2, build 26B5091g
- Metal support: confirmed by the system hardware report
- Installed development JDK: Oracle Java 25.0.4.1, ARM64
- Apple Command Line Tools: installed

The assistant considered this hardware suitable for developing and testing a native Metal renderer.

## Minecraft version

The user preferred the latest possible version and then specified that the latest release was Minecraft Java 26.3. That was the proposed target. Fabric was suggested by the assistant as the mod loader; no mod-loader implementation was completed.

## Clarification and final status

The user explicitly clarified: "i dont want you to make the mod, i just wanted to know if you could do it".

The assistant acknowledged the misunderstanding and stopped development-related work. No mod was built or installed, and no renderer was tested in Minecraft. A temporary Git directory had been initialized at `/private/tmp/minecraft-metal-26.3` during preliminary investigation; it was not a completed mod.

The only requested deliverable after that clarification was a Desktop folder named `MetalMC` containing this Markdown summary. This conversation does not authorize further mod development or installation.
