# MinestomRewind — Agent Guide

## Objectif

MinestomRewind est un fork de [Minestom](https://github.com/Minestom/Minestom) modifié pour cibler **Minecraft 1.9.4 (protocol 110)** au lieu des versions récentes de Minecraft.

## Build

- **Java 11+**, Gradle (wrapper: `./gradlew`)
- Compiler : `./gradlew compileJava` ou `./gradlew build`
- Générer le JAR : `./gradlew shadowJar`
- Lancer : `java -jar build/libs/MinestomRewind-1.0-SNAPSHOT.jar`

## Structure du projet

```
src/main/java/net/minestom/server/
├── MinecraftServer.java          # Point d'entrée, constantes protocol (PROTOCOL_VERSION=110)
├── network/
│   ├── netty/                    # Pipeline Netty (encodage, compression, chiffrement)
│   ├── packet/
│   │   ├── server/               # Packets serveur → client
│   │   │   ├── ServerPacketIdentifier.java  # Tous les IDs de packets (0x00-0x4B)
│   │   │   ├── login/            # Packets login
│   │   │   ├── play/             # ~70 packets play (dont ChunkDataPacket)
│   │   │   └── status/           # Packets status
│   │   └── client/               # Packets client → serveur
│   └── player/                   # Connexions joueur
├── instance/                     # Chunks, blocs, instances
│   ├── DynamicChunk.java         # Implémentation Chunk concrète
│   ├── palette/
│   │   ├── PaletteStorage.java   # Stockage des blocs (Section[])
│   │   └── Section.java          # Section 16×16×16 (short[] blocks de 4096)
│   └── batch/ChunkBatch.java     # Mise à jour par lots
├── utils/
│   ├── PacketUtils.java          # createFramedPacket, compressBuffer
│   └── binary/BinaryWriter.java  # Writer pour la sérialisation des packets
└── entity/Player.java            # Gestion des joueurs
```

## Ce qui a déjà été fait

### ChunkDataPacket (0x20) — adapté pour 1.9.4

Fichiers modifiés :
- `ChunkDataPacket.java` — Réécriture complète du format :
  - **Bitmask :** `short` → `VarInt`
  - **Données de section :** `short[4096]` bruts → format **palette** (bitsPerBlock + palette + longs compactés)
  - **Lumière :** déplacée **dans chaque section** (blockLight + skyLight par section)
  - **Block entities :** `VarInt(0)` ajouté à la fin du packet
- `PacketUtils.java` — Buffer initial porté de 40k → 200k octets

Le stockage interne (`Section.short[] blocks`, `PaletteStorage`) est inchangé ; la conversion vers le format palette se fait à la volée dans `ChunkDataPacket.writeSection()`.

## Prochains packets à vérifier/adapter pour 1.9.4

### Prioritaires (problèmes potentiels identifiés)

| Packet | Fichier | Problème |
|--------|---------|----------|
| **JoinGamePacket** (0x23) | `network/packet/server/play/JoinGamePacket.java` | Vérifier la structure 1.9.4 (difficulté, gamemode, dimension, etc.) |
| **RespawnPacket** (0x33) | `network/packet/server/play/RespawnPacket.java` | Vérifier la structure 1.9.4 |
| **PlayerPositionAndLookPacket** (0x2E) | `network/packet/server/play/PlayerPositionAndLookPacket.java` | Hardcode `writeVarInt(1)` au lieu d'un téléport ID aléatoire |
| **StatisticsPacket** (0x07) | `network/packet/server/play/StatisticsPacket.java` | Utilise un format `String name + int value` — en 1.9.4 c'est `VarInt category + VarInt id + int value` |
| **EntityEquipmentPacket** (0x3C) | `network/packet/server/play/EntityEquipmentPacket.java` | Pas de slot `OFF_HAND` (1.9.4 : 0=main, 1=offhand, 2-5=armure) |
| **EntityAnimationPacket** (0x06) | `network/packet/server/play/EntityAnimationPacket.java` | Pas de `SWING_OFF_HAND` (id 3 en 1.9.4) |
| **ScoreboardObjectivePacket** (0x3F) | `network/packet/server/play/ScoreboardObjectivePacket.java` | Le format du type a changé (string "integer"/"hearts" au lieu d'un int) |
| **TitlePacket** (0x45) | `network/packet/server/play/TitlePacket.java` | Vérifier les actions (VarInt en 1.9.4) |
| **CombatEventPacket** (0x2C) | `network/packet/server/play/CombatEventPacket.java` | Implémentation incomplète (seul DEATH est supporté) |

### Client play packets IDs à vérifier

| ID | Packet client 1.9.4 | Statut |
|----|---------------------|--------|
| 0x10 | Vehicle Move | Non implémenté |
| 0x11 | Steer Boat | `ClientSteerVehiclePacket` enregistré à 0x15, pas à 0x11 — **probablement faux** |

### Autres vérifications

- **BossBar** : Le packet BossBar (0x0C) n'existe pas dans ce projet mais est optionnel
- **MapDataPacket (0x24)** : Vérifier le format 1.9.4
- **PluginMessagePacket (0x18)** : Vérifier le format 1.9.4
- **SoundEffectPacket (0x46)** : Vérifier le format 1.9.4

## Architecture clé

### Compression réseau

- Seuil : 256 octets (configurable via `MinecraftServer.setCompressionThreshold()`)
- La compression est gérée au niveau Netty (zlib/deflate niveau 3)
- Les packets passent par `PacketUtils.createFramedPacket()` qui sérialise → (optionnellement) compresse → frame
- Un cache (`TemporaryCache` 30s) évite de re-sérialiser les chunks pour chaque joueur

### Pipeline d'envoi d'un packet

```
ServerPacket.write(writer)
→ PacketUtils.createFramedPacket()
   → writePacket() → [VarInt packetId] [body]
   → compressBuffer() → [VarInt 0|uncompressedLen] [raw/zlib]
   → frameBuffer() → [VarInt totalLength] [compressed payload]
→ FramedPacket → Netty (GroupedPacketHandler → ... → socket)
```

### Gestion des chunks

- `PaletteStorage` : 16 `Section[]` (= 16×16×256 vertical)
- `Section` : `short[4096]` en interne, index = `y<<8 | z<<4 | x`
- `ChunkDataPacket.write()` convertit les `short[]` en format palette à l'écriture
- `Chunk.createFreshPacket()` / `getFreshFullDataPacket()` / `getFreshPartialDataPacket()`
- `DynamicChunk.createFreshPacket()` clone le `PaletteStorage` et crée un `ChunkDataPacket`

## Conventions de code

- Les packets étendent `ServerPacket` (interface avec `write()` et `getId()`)
- Les writers sont `BinaryWriter` (encapsule `ByteBuf`)
- `Utils.writeVarIntBuf(ByteBuf, int)` pour écrire des VarInt directement dans un ByteBuf
- `writer.writeVarInt(int)` pour écrire des VarInt via BinaryWriter
- `writer.getBuffer()` pour obtenir le ByteBuf sous-jacent
- Utilisation intensive de **fastutil** (`Int2IntOpenHashMap`, `IntList`, etc.)
- Les IDs de packets sont dans `ServerPacketIdentifier.java`

## Ressources

- [wiki.vg — Protocol 1.9.4](https://wiki.vg/index.php?title=Protocol&oldid=14044)
- Le packet `data/1.8.json` contient des données d'achievements 1.8 (legacy)
- `prismarine-minecraft-data/` est un sous-module avec des données mincraft

## Commandes utiles

```bash
./gradlew compileJava          # Compiler
./gradlew build                # Build complet
./gradlew shadowJar            # JAR exécutable
./gradlew test                 # Tests unitaires
```
