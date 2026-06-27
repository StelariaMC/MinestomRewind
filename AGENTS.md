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

### Metadata — adapté pour 1.9.4

Fichiers modifiés :
- `Metadata.java` — Types corrigés pour 1.9.4 (TYPE_VARINT=1, TYPE_CHAT=4, TYPE_BOOLEAN=6, TYPE_ROTATION=7, TYPE_POSITION=8) ; Chat sérialisé en JSON via Adventure
- `EntityMetaDataPacket.java` — Terminaison `0xFF` au lieu de `0x7F`
- `SpawnPlayerPacket.java` — Terminaison `0xFF`
- `SpawnMobPacket.java` — Terminaison `0xFF`
- `PlayerMeta.java` — Indices corrigés pour 1.9.4

### EntityEquipmentPacket (0x3C) — adapté pour 1.9.4

- Slot passe de `writeShort` à `writeVarInt`
- Enum réorganisé : `MAIN_HAND(0), OFF_HAND(1), BOOTS(2), LEGGINGS(3), CHESTPLATE(4), HELMET(5)`
- `EquipmentHandler.java` : ajout du cas `OFF_HAND`

### writeItemStack (NBTUtils) — format 1.9.4 (confirmé)

- Slot vide : `writeShort(-1)` (pas Boolean)
- Slot présent : `writeShort(itemId)` + `writeByte(count)` + `writeShort(damage)` + NBT optionnel
- Le format Boolean+VarInt (1.13+) avait été appliqué puis reverté

### SpawnPlayerPacket (0x05) — adapté pour 1.9.4

- Position : `writeInt(x*32)` → `writeDouble(x)` (Decimal au lieu de fixed-point)

### SpawnMobPacket (0x0C) — adapté pour 1.9.4

- Ajout du champ `UUID`
- `writeByte(entityType)` conservé (u8 en 1.9.4, pas VarInt)
- Position : `writeInt(x*32)` → `writeDouble(x)`

### ClientUseItemPacket (0x1D) — ajouté

- Nouveau packet pour gérer le clic droit avec un item
- Listener `UseItemListener.java` enregistré

### ClientSettingsPacket (0x04) — adapté pour 1.9.4

- Ajout du champ `mainHand` (VarInt) — résout "Packet 0x4 not fully read"

### EntityTeleportPacket (0x49) — adapté pour 1.9.4

- Position : `writeInt(x*32)` → `writeDouble(x)` (résout la déconnexion des joueurs au join)

### EntityRelativeMovePacket (0x25) — adapté pour 1.9.4

- DeltaX/Y/Z : `writeByte(delta)` → `writeShort(delta)`
- **Précision des deltas :** `* 32.0` → `* 4096.0` (le format 1.9.4 utilise des shorts en 1/4096e de bloc, pas 1/32 comme en 1.8)

### EntityLookAndRelativeMove (0x26) — adapté pour 1.9.4

- DeltaX/Y/Z : `writeByte(delta)` → `writeShort(delta)` (utilise `getRelativeMove()` → corrigé avec 4096)

### SpawnObjectPacket (0x00) — adapté pour 1.9.4

- Ajout du champ `UUID`
- Position : `writeInt(x*32)` → `writeDouble(x)`

## Prochains packets à vérifier/adapter pour 1.9.4

### Prioritaires (problèmes potentiels identifiés)

| Packet | Fichier | Problème |
|--------|---------|----------|
| **JoinGamePacket** (0x23) | `network/packet/server/play/JoinGamePacket.java` | Vérifier la structure 1.9.4 (difficulté, gamemode, dimension, etc.) |
| **RespawnPacket** (0x33) | `network/packet/server/play/RespawnPacket.java` | Vérifier la structure 1.9.4 |
| **StatisticsPacket** (0x07) | `network/packet/server/play/StatisticsPacket.java` | Utilise `String name + int value` — en 1.9.4 c'est `VarInt category + VarInt id + int value` |
| **ScoreboardObjectivePacket** (0x3F) | `network/packet/server/play/ScoreboardObjectivePacket.java` | Le type a changé (string "integer"/"hearts" au lieu d'un int) |
| **TitlePacket** (0x45) | `network/packet/server/play/TitlePacket.java` | Vérifier les actions (VarInt en 1.9.4) |
| **CombatEventPacket** (0x2C) | `network/packet/server/play/CombatEventPacket.java` | Implémentation incomplète (seul DEATH supporté) |
| **SoundEffectPacket** (0x46) | `network/packet/server/play/SoundEffectPacket.java` | Vérifier format 1.9.4 |

### Client play packets IDs à vérifier

| ID | Packet client 1.9.4 | Statut |
|----|---------------------|--------|
| 0x10 | Vehicle Move | Non implémenté |
| 0x11 | Steer Boat | `ClientSteerVehiclePacket` enregistré à 0x15, pas à 0x11 — **probablement faux** |

### Autres vérifications

- **BossBar** : Le packet BossBar (0x0C) n'existe pas dans ce projet mais est optionnel
- **MapDataPacket (0x24)** : Vérifier le format 1.9.4
- **PluginMessagePacket (0x18)** : Vérifier le format 1.9.4
- **readItemStack (BinaryReader)** : Format 1.9.4 probablement non adapté pour la réception

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
- **Paper 1.9.4 (NMS) :** `/home/mathip/dev/java/AZPaper/work/1.9.4/net/minecraft/server/`
  - `PacketPlayOutEntity.java` — sérialisation des packets de mouvement
  - `EntityTrackerEntry.java` — décision relative move vs teleport (deltas en 1/4096, seuil ±32768)
  - `EntityTracker.java` — conversion `MathHelper.d(loc * 4096.0)`

## Commandes utiles

```bash
./gradlew compileJava          # Compiler
./gradlew build                # Build complet
./gradlew shadowJar            # JAR exécutable
./gradlew test                 # Tests unitaires
```
