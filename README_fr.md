<div align="center">

# devx pour Android

**Un assistant IA de poche et une télécommande pour devx sur votre ordinateur.**  
Sans compte, sans serveur de notre part. Vos clés et vos discussions restent sur le téléphone.

<br/>

[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)
[![Download APK](https://img.shields.io/badge/download-APK-00c2d1.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)
[![Languages](https://img.shields.io/badge/languages-9-4f8cff.svg?style=flat-square&labelColor=0a0c11)](#)
[![devx: MIT](https://img.shields.io/badge/devx-MIT-4f8cff.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/blob/main/LICENSE)

<br/>

[English](README.md) &nbsp;•&nbsp; [Русский](README_ru.md) &nbsp;•&nbsp; [Українська](README_uk.md) &nbsp;•&nbsp; [Deutsch](README_de.md) &nbsp;•&nbsp; [Español](README_es.md) &nbsp;•&nbsp; **Français** &nbsp;•&nbsp; [Português](README_pt.md) &nbsp;•&nbsp; [中文](README_zh.md) &nbsp;•&nbsp; [日本語](README_ja.md)

</div>

---

## En un coup d'œil

<table>
<tr>
<td valign="top" width="25%"><b>Assistant de poche</b><br/>Le modèle de votre choix, voix, photos et fichiers, mémoire, compétences, MCP, recherche web.</td>
<td valign="top" width="25%"><b>Carnets</b><br/>Apprendre ou réunir des idées : sources, plan d'étude, cartes, résumé audio, réponses qui citent.</td>
<td valign="top" width="25%"><b>Voix et téléphone</b><br/>Alarmes, appels, messages, lieux et météo, tâches planifiées, widgets.</td>
<td valign="top" width="25%"><b>Télécommande de devx</b><br/>Les mêmes sessions que dans le terminal : réponses, approbations, plans, fichiers.</td>
</tr>
</table>

## Télécharger

L'application a sa propre page sur Termux-Dev, à part des versions de devx (l'application et devx ont des versions séparées). Chaque version y est un fichier avec sa somme de contrôle ; les nouvelles s'ajoutent à côté des anciennes et un fichier n'est jamais remplacé :

**[Releases → devx for Android](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)** → le fichier le plus récent, `devx-android-X.Y.Z.apk`

Ouvrez-le sur le téléphone et autorisez l'installation depuis cette source. Android 8.0 (API 26) ou plus récent. Les mises à jour s'installent par-dessus l'ancienne version : chaque version est signée avec la même clé, et une version publiée n'est jamais remplacée.

## Démarrer

1. Ouvrez l'application et dites-lui votre nom.
2. **Réglages → Fournisseurs → Ajouter un fournisseur.** Choisissez un préréglage ou saisissez une adresse et une clé d'API (compatible OpenAI ou format Anthropic ; le format est détecté seul). Chargez les modèles et choisissez-en un.
3. Écrivez, ou touchez le microphone.

La clé d'API est conservée chiffrée sur le téléphone (Android Keystore) et ne va qu'au fournisseur que vous avez ajouté.

## Ce qu'elle fait

### Discussion

- Plusieurs fournisseurs et modèles, effort de raisonnement par modèle, branches lorsque vous modifiez un message, discussions temporaires, discussions verrouillées par le verrouillage d'écran ou un code, projets (travail continu avec leurs propres instructions et connaissances) et carnets (ci-dessous).
- Photos, vidéo, fichiers et appareil photo dans un message ; visionneuse PDF et texte ; export en Markdown ou PDF.
- Mémoire : l'assistant garde des notes utiles et un résumé sur vous, uniquement sur le téléphone. Vous pouvez les lire, les modifier et les supprimer. Le résumé se renouvelle seul en arrière-plan et montre ce qui y est nouveau ; une note qui cessera bientôt d'être vraie (un examen vendredi) peut expirer ; quand il y a beaucoup de notes, celles qui touchent votre question sont envoyées en premier.
- Compétences (guides écrits que l'assistant lit quand une tâche correspond) : intégrées, importées (`.skill` ou `SKILL.md`) ou écrites par vous.
- Serveurs MCP en https (GitHub, documentation et autres) avec approbations et un interrupteur lecture seule.
- Recherche web (il lit lui-même les pages de recherche ; une clé gratuite Tavily ou Brave dans les Réglages la rend nettement plus précise), fichiers et archives, un terminal et Python dans l'application, dans un dossier à part. Chacun peut être désactivé.
- Formulaires que l'assistant dessine pour vous : questions avec libellés et indices, choix (un ou plusieurs), curseurs et interrupteurs ; ce que vous remplissez est conservé, et un formulaire envoyé reste envoyé.
- Les noms des discussions s'écrivent pendant que la réponse s'écrit, avec une petite animation ; la discussion où vous êtes est marquée dans la liste.
- Réponses riches : cartes, tableaux, graphiques, cartes géographiques, météo, minuteurs, listes de contrôle, diagrammes Mermaid, fiches d'applications de Google Play et F-Droid. Messages d'accueil changeants sur l'écran d'accueil.
- Un éditeur plein écran avec aperçu Markdown pour les longs messages. Sélectionnez plusieurs discussions pour les supprimer ou les archiver.
- Tapez `@` dans le champ : un menu de ce que vous pouvez désigner (un carnet, un projet, un fichier de la bibliothèque, une tâche planifiée). Un carnet ou un projet devient une partie de la phrase, dans sa couleur, et l'assistant reçoit ce qu'il contient. À la voix, dites simplement son nom.
- Une seule recherche dans les discussions, carnets, projets, fichiers et sources, y compris par ce qui est écrit dans les documents, PDF et notes.
- Collez des images et des fichiers directement dans le champ du message (depuis le presse-papiers ou le clavier). « Demander à devx » sur un texte sélectionné ne touche pas à votre presse-papiers.
- Sauvegarde et restauration de tout dans un seul fichier : discussions, carnets et projets, mémoire, compétences, caractère, pièces jointes et réglages. Les clés d'API et les jetons n'y entrent que si vous définissez un mot de passe (ils sont chiffrés avec lui). Avant de restaurer, l'appli montre ce que contient le fichier et ce qu'elle va remplacer. Une copie hebdomadaire se fait toute seule dans un dossier de votre choix et, si vous le souhaitez, est aussi envoyée dans un dossier WebDAV (Nextcloud, un NAS) ; les quatre dernières sont conservées.
- Épingler et archiver des discussions, verrouiller une discussion précise, ajouter une discussion à l'écran d'accueil. « Demander à devx » sur un texte sélectionné cite le passage sous forme de carte dans votre message suivant.
- L'assistant travaille avec des fichiers dans un dossier à part : il crée et lit des fichiers, fait et décompresse des archives, lit un PDF page par page et regarde ses images, retouche des images, et décompresse, modifie, recompresse et signe des APK. Les approbations peuvent se donner depuis la notification elle-même (Autoriser / Refuser).
- Quatre widgets (une barre pour demander, des boutons rapides, les dernières discussions, les cartes à réviser) et deux tuiles de réglages rapides (demander, voix).

### Carnets et bibliothèque

- Un carnet est un lieu pour apprendre ou réunir des idées sur un sujet. Les carnets d'*idées* gardent des sources et des discussions sur un sujet ; les carnets d'*étude* ajoutent un objectif, un plan de leçons et votre progression. Les projets sont à part : ils servent au travail continu.
- Sources : fichiers, photos (aussi depuis l'appareil photo) et pages web. Chacune reçoit une courte description de l'assistant ; demandez-lui de trouver des sources sur le web et il ajoute les meilleures pages. L'assistant peut aussi retirer une source (vous confirmez), la renommer, modifier le texte d'une note ou d'une page enregistrée, et garder un texte de son cru (un résumé, un plan) comme nouvelle source.
- Les réponses sur un carnet citent leurs sources par de petites pastilles (`notes.pdf · 12`) ; un toucher ouvre la source, un PDF à cette page.
- Un carnet d'étude enseigne pas à pas, vérifie avec des formulaires, écrit lui-même le plan et tient des cartes : les questions reviennent à réviser quand il est temps (mieux vous en connaissez une, plus tard elle revient). Les cartes ont un écran dédié : la question, un toucher retourne la carte, quatre boutons (à revoir, difficile, bien, facile) indiquent quand elle reviendra, et un bilan à la fin ; cela fonctionne sans le modèle et ne coûte rien. Un rappel quotidien est une notification avec le nombre de cartes à réviser.
- L'assistant peut modifier un carnet ou un projet (nom, icône, couleur, description, instructions, objectif) et, après discussion, en proposer un nouveau : vous confirmez sur une carte.
- Partagez un lien, un texte, un PDF ou une image depuis n'importe quelle appli : devx propose une nouvelle discussion ou l'un de vos carnets, où cela devient une source (un lien est lu et conservé).
- Statistiques d'étude sur la page : jours d'affilée, les sept derniers jours en barres ; un widget d'écran d'accueil montre combien de cartes sont à réviser et lance la révision. Les cartes et le résumé du carnet peuvent s'écouter (un *résumé audio* est écrit pour être écouté, puis lu à voix haute), et un carnet s'exporte en un fichier Markdown.
- Les cartes peuvent avoir une image et on peut y répondre à voix haute. Elles arrivent d'Anki (`.apkg` ou texte) et de CSV, et repartent en paquet Anki avec images, en texte Anki ou en CSV. Une *carte des notions* (dans les carnets d'étude comme dans les carnets d'idées) est une image vivante, comme dans Obsidian : les notions flottent, entraînent leurs voisines quand vous les déplacez, puis se calment ; pincez pour zoomer, touchez-en une pour éclairer ses liens et ouvrir sa fiche (*Explique* et, dans un carnet d'étude, *Interroge-moi*, colorée selon votre degré de maîtrise ; dans un carnet d'idées, *Dans mes sources*). On peut la travailler à la main : maintenez une notion pour la renommer ou la supprimer, maintenez un endroit vide pour en ajouter une, déposez-en une sur une autre pour les relier, et cherchez par nom. Un carnet peut être archivé ou copié pour recommencer (ses sources et leçons, pas ses discussions).
- La bibliothèque garde chaque fichier que vous avez joint ou que l'assistant a créé : recherche, filtres, où il a servi, joindre à une nouvelle discussion, supprimer partout. PDF, Word, diapositives, tableurs, texte, images et sites s'ouvrent dans la visionneuse intégrée ; les originaux Office sont conservés.

### Apparence, caractère et sécurité

- L'apparence a sa propre page : trois choix rapides (système, noir, clair) et un écran d'autres thèmes (gris foncé, Minuit, Forêt, Crépuscule, Braise, Océan, Papier, Sakura, Menthe). Le thème peut changer tout seul selon l'heure (un de jour, un de nuit), et le changement est animé. Le verre flouté peut être désactivé : les boutons restent alors opaques.
- Caractère : choisissez une manière (calme, sans fioritures, détaillée, amicale, professionnelle, franche, mentor), affinez le ton, donnez un nom à l'assistant et écrivez vos propres instructions. Un toucher remet tout à zéro.
- Verrouillage de l'application par le verrouillage d'écran, un code ou la biométrie ; on peut aussi verrouiller des discussions isolées, et la liste des applis récentes masque l'image tant qu'un verrou est actif.
- Les photos par message, les notifications de réponses et d'approbations, et le droit pour un ordinateur de fonctionner en mode « tout » se règlent dans les réglages.
- Données et sécurité ont leur propre page : le verrou, les copies et la corbeille. Les discussions, projets et carnets supprimés restent 30 jours dans la corbeille et peuvent être remis (juste après la suppression, un message propose « Annuler »). Un fichier de discussion illisible est mis de côté au lieu d'être perdu, et vous en êtes informé. Ce dont l'appli s'est remise est listé dans **À propos → Diagnostic**.

### Assistant vocal

- Choisissez Devx comme assistant du téléphone : le bouton d'alimentation ou un balayage depuis un coin ouvre une fenêtre vocale transparente par-dessus n'importe quelle appli.
- Alarmes, minuteurs, rappels ; ouvrir des applis et des réglages système ; contacts, appels, messages et agenda (vous appuyez sur le dernier bouton) ; lampe torche, musique, volume ; luminosité et Ne pas déranger.
- Lieux, itinéraires et météo d'OpenStreetMap et Open-Meteo, sans clés. 2GIS et Google Maps peuvent s'ajouter avec votre propre clé.
- Regarde votre galerie, lit les notifications à l'écran, travaille dans un dossier de votre choix, mais seulement quand vous le demandez et seulement si vous l'avez autorisé.
- Tâches planifiées : « chaque matin, envoie-moi les nouvelles sur l'IA ». Elles s'exécutent seules et arrivent sous forme de discussion et de notification.
- La vitesse d'élocution, la voix et un modèle rapide à part pour parler se trouvent dans **Réglages → Assistant vocal**.
- Une voix naturelle de votre fournisseur (voix au format OpenAI ou Gemini) peut remplacer la voix robotique du téléphone : choisissez le fournisseur, le modèle vocal (dans sa liste ou en saisissant un nom) et la voix, et écoutez un exemple. En cas d'échec, la raison s'affiche et la voix du téléphone lit en attendant.
- Touchez la pastille pour écrire au lieu de parler ; maintenez-la et balayez vers le haut pour ouvrir l'application avec la même discussion (un indice au-dessus de la pastille montre toujours comment). Le microphone a ses propres sons doux au lieu du bip du service de reconnaissance vocale.
- « Continuer à écouter après une réponse » (mains libres) et la langue que vous parlez, choisie à part de celle de l'application.
- Les tâches planifiées ont un écran dédié : une heure, les jours, le modèle de chacune, « exécuter maintenant ».

### Devx Remote

- Pilotez devx sur votre ordinateur : chaque discussion, réponses et raisonnement en direct, cartes d'approbation, questions, le plan (avec *Go and compact context*), modes plan/agent/YOLO, modèle et fournisseur, contexte et coût, `/compact` et autres commandes. Fichiers et photos vont dans les deux sens.
- Fonctionne terminal fermé. Le téléphone atteint l'ordinateur directement (même Wi-Fi, un VPN comme Tailscale, un port redirigé) ou par un relais public gratuit. Tout est chiffré de bout en bout.
- Démarrez une nouvelle session sur l'ordinateur dans un dossier de votre choix ; ajoutez un ordinateur par adresse (VPN, port redirigé) et pas seulement par QR code.

Sur l'ordinateur :

```
devx remote start     # l'hôte, continue de tourner sans terminal
devx remote pair      # un QR code : scannez-le dans l'application, confirmez les six chiffres sur l'ordinateur
```

Un téléphone se retire avec `/phones` dans devx ou `devx remote revoke <name>`. Plus de détails dans le [README de devx](https://github.com/apvcode/Termux-Dev#readme).

## Langues

English, Русский, Deutsch, Español, Français, 日本語, Português, Українська, 中文 : les mêmes neuf que devx. **Réglages → Langue**. La langue détermine aussi ce que le microphone écoute.

## Confidentialité

- Discussions, mémoire, compétences et clés sont sur le téléphone. Rien ne va chez nous ; il n'y a pas de serveur de notre part.
- Vos messages vont au fournisseur que vous avez choisi, et à un serveur MCP ou à un service de cartes seulement quand un outil en a besoin.
- Galerie, notifications, position, contacts et dossier ne sont lus que lorsque vous le demandez, et chacun peut être désactivé.
- Les appels, messages et événements d'agenda ne sont jamais envoyés sans votre dernier toucher.

## Compiler

```
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

JDK 17 et le SDK Android. Signature : placez `signing.properties` (storeFile, storePassword, keyAlias, keyPassword) dans `~/.config/devx-android/`, ou indiquez-le avec `DEVX_SIGNING_PROPERTIES`, ou utilisez `keystore.properties` dans le dossier du projet. Sans l'un d'eux, la compilation est signée avec la clé de débogage et ne peut pas mettre à jour une version installée. Gardez la clé en lieu sûr : c'est elle qui permet aux mises à jour de s'installer par-dessus l'ancienne version.

L'application a ses propres versions. Une seule commande la compile, vérifie la clé de signature et ajoute `devx-android-X.Y.Z.apk` à la page `android-app` de Termux-Dev (elle demande d'abord ; un fichier déjà présent n'est jamais remplacé ; `--dry-run` montre ce qu'elle ferait) :

```
node scripts/release-android.mjs      # à lancer dans le dossier devx
```

## Technologies

Kotlin, Jetpack Compose, OkHttp, Android Keystore. Les icônes sont des [Tabler Icons](https://tabler.io/icons) (MIT) ; Mermaid est inclus pour les diagrammes (MIT). Les mentions sont dans [NOTICE](NOTICE).

## Licence

L'application n'a pas encore de fichier de licence propre ; devx lui-même est sous MIT. Les mentions des tiers (Tabler Icons, Mermaid) sont dans [NOTICE](NOTICE), les licences des polices dans [licenses/](licenses).
