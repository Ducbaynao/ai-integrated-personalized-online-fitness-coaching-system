# P7-012 Vietnamese Font Rendering Handoff — 2026-11-26

- **Checkout:** `C:\VScode\CodePTIT\Đồ Án Tốt Nghiệp\p7-012-vietnamese-fonts` (standalone clone of `origin/main`)
- **Branch / Base HEAD:** `fix/p7-012-vietnamese-fonts` @ `f8e564dca39a27acdc5f7192e31dbb399d471216` (remote `main` at clone time)
- **Git:** working-tree edits only; nothing staged, committed or pushed; no PR.
- **Scope:** P7-012 only (font assets, loading, typography tokens and directly related documentation). P7-007 (keyboard/IME, closed as environment), P7-016 (copy/localisation), P7-004 (Figma redesign) and the timezone/meal patch remain separate.
- **Device status:** Android (`Pixel_7`) and web runtime/visual checks are **`DEFERRED_FOR_RAM`**. The metadata and glyph tests below are not a `Pixel_7` PASS.

---

## 1. Inventory at base `f8e564d`

| Item | Finding |
|---|---|
| Font package | `@expo-google-fonts/dm-sans@0.4.2` (only font package; no font files under `apps/mobile/assets`) |
| Loader | `expo-font@57.0.4` `useFonts` in `apps/mobile/src/app/_layout.tsx` loads `DMSans_400Regular`, `_500Medium`, `_600SemiBold`, `_700Bold` |
| Tokens | `apps/mobile/src/design-system/tokens/typography.ts`: `fontFamily.regular/medium/semiBold/bold` → the four DM Sans registration names; `typography.*` styles spread these families |
| Consumers | 48 non-test source files spread `typography.*` or `fontFamily.*`; no other hardcoded `fontFamily` except `Fonts.mono` (system monospace) in the unused starter `themed-text.tsx` |
| Text without tokens | Uses the platform default font (Roboto on Android, browser `sans-serif` on web), which covers Vietnamese |
| Italic | 26 `fontStyle: 'italic'` sites, all with upright token families; no italic file is loaded (synthetic oblique on all platforms, before and after this change) |
| Weight overrides | 31 files combine a `typography.*` family with an explicit `fontWeight` (pre-existing; see §6) |
| Loading/error path | `fontsReady = fontsLoaded || Boolean(fontError)`; web loader (`FontFaceObserver`) rejects after 12 s, so a failed font load degrades to system text instead of hanging |
| Admin Web | Out of scope (no DM Sans reference found in Admin code) |

## 2. Proven cause

The cmap of every DM Sans TTF shipped by `@expo-google-fonts/dm-sans@0.4.2` was inspected with fontTools 4.54.1. The result was identical for all 18 weight/style files: 403 code points.

- Present: Latin-1 and Latin Extended-A letters, e.g. `à á â ã è é ê ì í ò ó ô õ ù ú ý ă đ ĩ ũ` and their capitals: **58 / 146** precomposed Vietnamese letters.
- Missing: **`Ơ ơ Ư ư`** (U+01A0/01A1/01AF/01B0), the whole Latin Extended Additional range U+1EA0–U+1EF9 (`ạ ả ấ ầ ẩ ẫ ậ … ự ỳ ỵ ỷ ỹ`) and the combining marks U+0309, U+031B, U+0323. That is 88 letters.

The official Google Fonts metadata for DM Sans (`google/fonts` `ofl/dmsans/METADATA.pb`, upstream `googlefonts/dm-fonts` @ `d0520ba`) lists only the `latin` and `latin-ext` subsets. The installed `@expo-google-fonts/dm-sans@0.4.2` assets and the current official Google Fonts upright variable TTF inspected independently both lack the required glyphs. This evidence does not establish that every past or future official DM Sans build lacks Vietnamese support.

Result: every token-styled text (48 files covering Student and Trainer screens) mixes DM Sans with per-glyph fallback from the system font for `ơ`, `ư` and most tone-marked letters. The fallback glyphs have different shapes, weights and metrics. This matches the report that `ư` and `ơ` look wrong across the app. Text that does not use tokens renders correctly because it uses the system font, which explains why the defect looked inconsistent.

Ruled out as the cause:
- **Encoding:** source strings are valid UTF-8 NFC; the jest render output shows correct code points.
- **Font not loaded:** loading only gates readiness; the glyphs are absent from the file itself.
- **Input method:** P7-007 is a separate, closed issue.
- **Wrong family or weight mapping:** each token maps to a distinct file of the correct weight.

## 3. Change

The installed DM Sans assets do not cover the required Vietnamese glyphs. Following the recorded fallback decision, the app font is now **Be Vietnam Pro**:

- Official Google Fonts family (`ofl/bevietnampro/METADATA.pb` subsets: `latin`, `latin-ext`, `vietnamese`; upstream `bettergui/BeVietnamPro` @ `804e62d`), designed for Vietnamese, geometric sans-serif close in tone to DM Sans, 100–900 with italics.
- Supplied by `@expo-google-fonts/be-vietnam-pro@0.4.1` from the same publisher as the previous package (`expo/google-fonts`, package license `MIT AND OFL-1.1`).

| File | Change |
|---|---|
| `apps/mobile/package.json`, `package-lock.json` | Replace `@expo-google-fonts/dm-sans ^0.4.2` with `@expo-google-fonts/be-vietnam-pro ^0.4.1` (one lock entry; no other dependency moved) |
| `apps/mobile/src/design-system/tokens/typography.ts` | `fontFamily` values → `BeVietnamPro_400Regular/500Medium/600SemiBold/700Bold`; sizes and line heights unchanged |
| `apps/mobile/src/design-system/fonts.ts` (new) | `appFontAssets` keyed by the token family names, so a token cannot reference an unloaded family; one file per weight (no weight emulation) |
| `apps/mobile/src/app/_layout.tsx` | `useFonts(appFontAssets)`; readiness/error logic unchanged |
| `apps/mobile/src/__tests__/vietnameseFonts.test.tsx` (new) | See §4 |
| `docs/06-ui-ux/design-tokens.md`, `design-principles.md`, `references/superfit-reference.md`, `docs/07-development/features/01-mobile-foundation.md`, `.agent/skills/digital-fitness-ui/SKILL.md`, `references/review-checklist.md` | Name Be Vietnam Pro as the app font with the P7-012 reason. Historical B01 `VERIFIED` statements are annotated rather than rewritten. The SuperFit audit line saying the template uses DM Sans stays as observed fact |

No screen, copy, spacing, colour, navigation, enum/API value or stored data was changed. No characters were stripped or substituted.

### Source and license

- The copyright and SIL OFL 1.1 notice/link are embedded in every shipped TTF (`name` IDs 0/13/14: “Copyright 2021 The Be Vietnam Pro Project Authors (https://github.com/bettergui/BeVietnamPro)”, OFL 1.1 text pointer). OFL §2 permits this as the accompanying license in machine-readable metadata, so the app bundle needs no extra file. The package also carries `LICENSE_FONT` (full OFL) and `LICENSE` (MIT for the Expo loader code).
- No font file is vendored in the repository; the asset comes only from the npm package.
- The font is not modified or renamed, so the OFL Reserved Font Name rules do not apply.

## 4. Verification

### Glyph and metadata evidence (fontTools 4.54.1, read-only)

| Font file | cmap | Precomposed vi | Missing combining |
|---|---|---|---|
| DMSans 400/500/600/700 (and all other DM Sans files) | 403 | 58/146 | U+0309, U+031B, U+0323 |
| BeVietnamPro 400/500/600/700 + italics | 459 | **146/146** | none |

Be Vietnam Pro also has GSUB `ccmp`, GPOS `mark` and `mkmk`, and `DFLT` and `latn` scripts, which establishes the presence of positioning features for decomposed (NFD) input; actual shaping and visual positioning remain unverified on Android/web. OS/2 `usWeightClass` is 400/500/600/700 for the four token files.

### Automated test `vietnameseFonts.test.tsx`

- The font package is mocked from its own generated `index.js`, so each export resolves to the real TTF the bundle would load. The test then parses those bytes (cmap formats 4/12, OS/2, GPOS feature list). It does not restate the token values.
- For each token family (regular/medium/semiBold/bold) it checks:
  - registered and backed by an existing file;
  - upright file of the matching weight;
  - all 146 precomposed Vietnamese letters (`ă â ê ô ơ ư đ`, capitals, all five tones);
  - seven NFC samples, including `ă â ê ô ơ ư đ`, `Ă Â Ê Ô Ơ Ư Đ`, `TRƯỜNG HỢP ĐỔI HUẤN LUYỆN VIÊN`, `Người dùng ước lượng dinh dưỡng bữa ăn`;
  - the same seven samples as NFD, with `mark`/`mkmk` present.
- Root layout checks: `useFonts` receives exactly the token families; the session gate stays while fonts are pending; the app proceeds (no hang) when `useFonts` reports an error.
- **Negative control:** a temporary copy of the test pointing the same families at the DM Sans TTFs failed the coverage assertions on U+1EA0… (missing `Ạ ạ Ả ả …`). The copy was deleted afterwards.

### Commands run (`apps/mobile`)

| Command | Result |
|---|---|
| `npx jest src/__tests__/vietnameseFonts.test.tsx` (first version) | **PASS 68/68** |
| `npm run typecheck` (first version) | **FAIL**: test used Node `fs`/`Buffer`/`require.resolve` types; Mobile has no `@types/node`. Fixed by typing the used Node APIs locally (no new dependency) |
| `npm run typecheck` (after fix) | **PASS** (exit 0) |
| `npx eslint` on the four changed source/test files | **PASS** (exit 0, 0 warnings after moving imports to the top) |
| `npx jest src/__tests__/vietnameseFonts.test.tsx` (after typing fix) | **FAIL to compile**: jest-hoist rejected an inline type in the mock factory; replaced with a `mock`-prefixed type alias |
| *Heavy checks paused so Codex could run the timezone harness; resumed after Codex finished.* | |
| **Final, after the last edit:** `npx jest --runInBand src/__tests__/vietnameseFonts.test.tsx` | **PASS 1/1 suite, 68/68 tests** (exit 0) |
| `npx jest --runInBand vietnameseFonts accessibilityAndTokens sessionRestorationNavigation CapabilitySwitcher dateInputs` (font/token/loading-affected suites) | **PASS 5/5 suites, 99/99 tests** (exit 0, 17.6 s) |
| `npm run typecheck` | **PASS** (exit 0) |
| `npm run lint` | **PASS** (exit 0, 0 errors; 5 pre-existing `no-require-imports` warnings, all in untouched `CurrentGoalScreen.test.tsx`) |
| Root `npm run validate:ux-docs` | **PASS** (exit 0, 18 required files) |
| Root `git diff --check` | **PASS** (exit 0) |

No test snapshots contain font family names.

The full Mobile suite was not run; leave it for the checkpoint/CI.

## 5. Runtime retest required (DEFERRED_FOR_RAM)

On Android `Pixel_7` (Expo Go) and web at phone width, after a cold start (clear Metro cache) and after a refresh:

1. Screens that use tokens heavily: Sign In/Register, Student Onboarding/Profile, Current Goal tabs, Measurements, Progress, Nutrition, Trainer Home/Onboarding/Profile, date picker modal.
2. Check `ă â ê ô ơ ư đ` / `Ă Â Ê Ô Ơ Ư Đ` and tone-marked words in regular (body), medium (caption), semibold (label/h3) and bold (h1/h2). Confirm no glyph has a different shape or weight from its neighbours.
3. Check italic helper text (synthetic oblique) for acceptable rendering of `ơ ư` with tones.
4. Optional: simulate a font failure (offline web cold load) and confirm the app continues with system text after the loader timeout.
5. Compare line wrapping and truncation. Be Vietnam Pro is slightly wider than DM Sans, and fixed-width chips or badges may need review under P7-004 rather than in this fix.

## 6. Known limits and follow-ups (not changed here)

- **Cập nhật 2026-11-27:** mục `fontWeight` và italic bên dưới đã được xử lý trong [p7-followups-timezone-nutrients-font-handoff.md](p7-followups-timezone-nutrients-font-handoff.md) (inventory lại bằng AST, map sang `fontFamily.*`, thêm face italic Regular/Medium). Nội dung dưới giữ làm lịch sử.
- **Explicit `fontWeight` overrides (31 files)** combined with a single-weight token family can trigger synthetic bold on Android. This predates P7-012, is not a glyph defect, and fixing it means editing callers across features. Recommended follow-up: map overrides to the matching `fontFamily.*` token.
- **Italic:** no italic file is registered. The package ships italics (`BeVietnamPro_*_Italic`, also full Vietnamese coverage), so adding italic tokens is possible later if design requires a true italic.
- `themed-text.tsx` / `Fonts` in `constants/theme.ts` are starter leftovers using system fonts; they were not changed.
- Docs merge note: the main checkout has an unrelated local edit to `docs/06-ui-ux/references/superfit-reference.md`. This branch changes one line there (§“Có thể kế thừa”), so check for a conflict when integrating.

## 7. SHA-256 (original source patch before main integration)

Values computed after the final verification run. Font asset paths are relative to `apps/mobile/node_modules/@expo-google-fonts/` (installed from the lockfile entry `be-vietnam-pro-0.4.1.tgz`). This handoff file is not self-hashed.

| File | SHA-256 |
|---|---|
| `apps/mobile/package.json` | `5639bd60f7df0ed529fb1cd718e5a8344aa657974416f41beb85ab60952228dd` |
| `apps/mobile/package-lock.json` | `d25efd79bddda90342e88cbe9095f187396481ceeefd6d67f5eee8cc7080b0dd` |
| `apps/mobile/src/app/_layout.tsx` | `dd9dee14cf7e99f4732b06fec76bb409d2a4c3d95518b8e6b34f4f0523a56933` |
| `apps/mobile/src/design-system/fonts.ts` | `4dc72c2e8d262201f77ba71497f398bee55d12ac189493bd40510616a96587db` |
| `apps/mobile/src/design-system/tokens/typography.ts` | `0f057098849650328068489aeccaf59c95d1353874520e75b5992474b672601e` |
| `apps/mobile/src/__tests__/vietnameseFonts.test.tsx` | `52ab0df11ee32a9fc824151d57446f5adae884a25d05dfd2e2f8564eab2d7164` |
| `be-vietnam-pro/400Regular/BeVietnamPro_400Regular.ttf` | `cb6f53d6fd56316356c55b067474d9621c27b7182b124063b8f6eab6acef63bd` |
| `be-vietnam-pro/500Medium/BeVietnamPro_500Medium.ttf` | `f493144cb53c78c6f0819bc9b73de274486618ac609bc92b3b79d4efc76f95c6` |
| `be-vietnam-pro/600SemiBold/BeVietnamPro_600SemiBold.ttf` | `04db63d2a82dd4bb35ba25e00dab1d12febcd0e917856b174b71a24d6f0c9afa` |
| `be-vietnam-pro/700Bold/BeVietnamPro_700Bold.ttf` | `2fe6c3f9dbb65f61554d530c16b2eb4ec32ca437dc8f9999a6ec0f6403b30bfe` |
| `be-vietnam-pro/LICENSE_FONT` | `6b7f8f73609a25ea78c891e34cf37b06f8a676b7ea986e941e43b009110f2a85` |
| Reference: `dm-sans/400Regular/DMSans_400Regular.ttf` (removed) | `20ccb90498d8ca511bb0be31a74eccd5f29fbe1161852ef72781b703929e98ec` |


## 8. Independent review and integration — 2026-11-26

- Source checkout remains read-only on `fix/p7-012-vietnamese-fonts` at `f8e564dca39a27acdc5f7192e31dbb399d471216`, 10 modified and 3 untracked files, empty index. All six source/test/dependency hashes and five available replacement asset/license hashes matched the handoff. All 13 copied files matched source bytes before integration.
- Main integration baseline: `57e27f8f40ed385c931979e5b35cff660b9f9fa8` (includes PR #177). Branch: `codex/fix-p7-012-vietnamese-fonts`; separate checkout `p7-012-integration`. Root layout patch context overlapped the added QR imports; reapplied only the reviewed font import/map delta, preserving `Linking` and the complete QR capture effect. Kept all QR dependencies/scripts in package/lock. No product logic beyond the reviewed font delta was introduced.
- Independent fontTools 4.54.1 checks: each installed old 400/500/600/700 asset covers 58/146 Vietnamese precomposed letters; each replacement covers 146/146 plus all required NFD marks, correct upright weight, nonzero glyph IDs, and `mark`/`mkmk` features. Four old-font negative controls fail coverage as expected. Metadata checks do not prove shaping, visual appearance, or native fallback behavior.
- The current official DM Sans variable asset was also inspected: 403 cmap entries, missing the same relevant glyphs, SHA-256 `8cd08d97e89c24d0aa92edd2f0f4c8ee6195eee9b7c9f154865a58b02f0c1c0d`. Narrowed the original universal DM Sans claim above to the assets actually inspected.
- Sources: [Google Fonts DM Sans metadata](https://github.com/google/fonts/blob/main/ofl/dmsans/METADATA.pb), [official Be Vietnam Pro metadata](https://github.com/google/fonts/blob/main/ofl/bevietnampro/METADATA.pb), [OFL FAQ 1.10](https://openfontlicense.org/ofl-faq/) (font metadata license links), [Expo SDK 57 font API](https://docs.expo.dev/versions/v57.0.0/sdk/font/). TTF `name` IDs 0/13/14 contain copyright, license declaration and license URL; npm package includes full LICENSE_FONT and loader MIT LICENSE.
- Independent baseline test command: `NODE_OPTIONS=--max-old-space-size=1280 npm test -- --runInBand --testTimeout=15000 vietnameseFonts accessibilityAndTokens sessionRestorationNavigation CapabilitySwitcher dateInputs`: 5 suites, 99 tests PASS, 0 failures, exit 0. `npm ci --no-audit --no-fund`: exit 0, lockfile unchanged.
- Existing explicit fontWeight overrides and synthetic italic remain follow-ups. Native Android, web, visual, keyboard and Pixel_7 checks remain **DEFERRED_FOR_RAM**. No Backend local, Docker, Metro, emulator or QA stack was started.

### Integrated working-tree SHA-256

Values below describe this integration checkout after applying the delta to main; section 7 remains historical source-patch evidence. Package/lock and root layout differ because main added QR. Root layout is stored with LF locally; byte hashes describe this working tree, not a CRLF checkout elsewhere. TTF/license hashes remain identical to section 7 after an independent clean npm ci.

| File | SHA-256 |
|---|---|
| `.agent/skills/digital-fitness-ui/SKILL.md` | `75b21ae1013177616d9f90b361b81a67fd62d72319b0cc232c44e44ffd9199cc` |
| `.agent/skills/digital-fitness-ui/references/review-checklist.md` | `9aa7083c1f592d1b80c7e5ce6f3c2eb321402610f85bb9f462bf2bda862f51fa` |
| `apps/mobile/package-lock.json` | `3046c5aada3bf9ded88e6064b9a20800c3be65aeb58afea245c50728124f55f5` |
| `apps/mobile/package.json` | `36f5182fe0a9b62d241f0e5586cb143c102d7f20b373e6a4d6226a479e49ea7a` |
| `apps/mobile/src/app/_layout.tsx` | `40495b03fd233bf915b7d4c38f6d36a99e3fb2ca75ba9e6f1d8ad8efd29e907e` |
| `apps/mobile/src/design-system/tokens/typography.ts` | `0f057098849650328068489aeccaf59c95d1353874520e75b5992474b672601e` |
| `docs/06-ui-ux/design-principles.md` | `204b276f0f598a9d2333824af0b2af366cb02b871bf1a8903a9ce64c643a13b4` |
| `docs/06-ui-ux/design-tokens.md` | `ec0e35dc6c5755e2091fe6cc03a00aa54aa7d93aeeeb3af78a93b58c143f3b7d` |
| `docs/06-ui-ux/references/superfit-reference.md` | `601cbe7b60f97dc4bf56e370fc5c5dba6f929affa5a9acce9e3e9002a149fcdb` |
| `docs/07-development/features/01-mobile-foundation.md` | `624a283c88dd9c3f893d84d0e4f3cf2432bf0dd28798eada735672ae1a7fa2c8` |
| `apps/mobile/src/__tests__/vietnameseFonts.test.tsx` | `52ab0df11ee32a9fc824151d57446f5adae884a25d05dfd2e2f8564eab2d7164` |
| `apps/mobile/src/design-system/fonts.ts` | `4dc72c2e8d262201f77ba71497f398bee55d12ac189493bd40510616a96587db` |

### Independent final integration checks

Environment: Node 22.23.4, npm 10.9.9 on Windows; `NODE_OPTIONS=--max-old-space-size=1280`; checks ran sequentially. No concurrent local heavy test task was used. Heavy-test slot was handed back after completion.

| Check on main `57e27f8` + font patch | Result |
|---|---|
| Clean `npm ci --no-audit --no-fund` | exit 0; lockfile unchanged; installed TTF/LICENSE hashes match source |
| `npm test -- --runInBand --testTimeout=15000 vietnameseFonts accessibilityAndTokens sessionRestorationNavigation CapabilitySwitcher dateInputs` | 5 suites, 99/99 tests PASS, 0 failure; exit 0; 68.972 s |
| `npm run typecheck` | exit 0 |
| `npm run lint` | exit 0; 0 errors, **12 existing warnings** on this newer main |
| root `npm run validate:ux-docs` | exit 0; 18 required files |
| `git diff --check` | exit 0 |

All lint warnings are `@typescript-eslint/no-require-imports`: five in unchanged `CurrentGoalScreen.test.tsx` (2126, 2169, 2227, 2277, 2328), plus seven in unchanged `coachingQrScreens.test.tsx` introduced by main PR #177 (26, 52, 53, 577, 594, 618, 619). The original five-warning source report in section 4 remains historical evidence for the older baseline; it is not the count on current main. No font-patch file has a lint warning. No source assertions were weakened.

Independent scratch evidence remains at `C:\Users\ADMIN\.codex\tmp\p7012-independent` (npm-ci/focused baseline/integration/typecheck/lint/UX/diff logs, inventory/hash JSON, independent fontTools script and results, official DM Sans asset). No secrets or private user data were copied into this checkpoint. Full Mobile suite remains assigned to CI on the new head; no old PR CI is used as evidence for this patch.
