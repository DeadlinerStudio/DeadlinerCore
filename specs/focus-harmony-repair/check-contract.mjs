// Host execution + source contracts; does not simulate ArkUI recomposition or device material rendering.
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import assert from 'node:assert/strict';
import { stripTypeScriptTypes } from 'node:module';

const repo = process.argv[2];
assert.ok(repo, 'Pass the Harmony repository root.');
const read = p => fs.readFileSync(path.join(repo, 'entry/src/main/ets', p), 'utf8');
let checks = 0;
const check = (label, value) => { assert.ok(value, label); checks++; console.log('PASS ' + label); };
const sandbox = { Toast: { show() {} }, getContext: () => ({ eventHub: { emit() {} } }),
  AppStorage: { setOrCreate() {} } };
vm.createContext(sandbox);
const uiSource = read('pages/routes/focus/FocusUiState.ets').replace(/^import .*;\n/gm, '').replace(/\bexport /g, '');
const models = vm.runInContext(stripTypeScriptTypes(uiSource, { mode: 'transform' }) +
  '\n({FocusActiveUiState, FocusMiniBarPolicy, FocusRequestGate});', sandbox);
sandbox.FocusActiveUiState = models.FocusActiveUiState;

const running = { sessionUid: 'S', taskUid: 'T', taskTitle: 'Task', target: { kind: 'TASK', uid: 'T' },
  mode: 'STOPWATCH', state: 'RUNNING', elapsedSeconds: 12, remainingSeconds: null, habitOccurredOn: null,
  plannedDurationSeconds: null, canPause: true, canResume: false, canFinish: true,
  canFinishAndComplete: true, canFinishAndRecord: false, habitRecordContext: null };
const paused = { ...running, state: 'PAUSED', canPause: false, canResume: true };
const r = new models.FocusActiveUiState(running), p = new models.FocusActiveUiState(paused);
check('Running snapshot exposes pause', r.hasTimer && r.canPause && !r.paused && !r.canResume);
check('Paused snapshot exposes resume and paused label', p.hasTimer && p.paused && p.canResume && !p.canPause);
check('Null snapshot exits active UI', !new models.FocusActiveUiState(null).hasTimer);
check('Old snapshot was not mutated', r.canPause && !r.paused);
const h = new models.FocusActiveUiState({ ...running, target: { kind: 'HABIT', uid: 'H' },
  habitOccurredOn: '2026-10-12', canFinishAndComplete: false, canFinishAndRecord: true,
  mode: 'COUNTDOWN', plannedDurationSeconds: 1500 });
check('Habit joint action and countdown survive projection', h.isHabit && h.canJointFinish && h.countdown && h.plannedSeconds === 1500);
check('Habit attribution survives projection', h.habitDate === '2026-10-12');
check('Normal layout cannot expand collapsed miniBar', !models.FocusMiniBarPolicy.nextExpanded(false, true, false));
check('App-trigger callback preserves expanded state', models.FocusMiniBarPolicy.nextExpanded(true, false, false));
check('User click can expand', models.FocusMiniBarPolicy.nextExpanded(false, true, true));
check('User click can collapse', !models.FocusMiniBarPolicy.nextExpanded(true, false, true));
const gate = new models.FocusRequestGate(), old = gate.begin(), recent = gate.begin();
check('Late old response is rejected', !gate.accepts(old) && gate.accepts(recent));

// Execute actual Sheet command/query methods with deferred bridge responses (no ArkUI runtime).
const sheet = read('pages/routes/focus/FocusTimerSheet.ets');
function method(name, source = sheet) {
  const found = new RegExp('  private (?:async )?' + name + '\\(').exec(source);
  assert.ok(found, name);
  const start = source.indexOf('{', found.index);
  let depth = 1, end = start + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    else if (source[end] === '}') depth--;
  }
  return source.slice(found.index, end);
}
const Controller = vm.runInContext(stripTypeScriptTypes('class Controller {\n' +
  ['applyState', 'toggleCurrentTimer', 'jointFinishCurrentTimer', 'resolveDisplaySeconds', 'refreshState', 'runCommand']
    .map(name => method(name)).join('\n') + '\n}\nController;', { mode: 'transform' }), sandbox);
const state = timer => ({ timer, lastOutcome: 'APPLIED', error: null, selectedTarget: { kind: 'TASK', uid: 'T' },
  selectedTaskUid: 'T', selectedTaskTitle: 'Task' });
const c = new Controller();
Object.assign(c, { requestGate: new models.FocusRequestGate(), selectedSessionUid: '', selectedTaskUid: 'T',
  selectedKind: 'TASK', selectedTaskTitle: 'Task', isBusy: false, enteredTimer: false,
  recordingSessionUid: '', recordContext: null, tickCount: 0 });
c.applyState(state(running));
let releaseQuery;
const operations = [];
c.repository = {
  state: () => new Promise(resolve => { releaseQuery = resolve; }),
  execute: async request => { operations.push(request.operation); return state(request.operation === 'pause' ? paused : running); }
};
const staleQuery = c.refreshState();
await c.runCommand('pause');
check('Pause applies projected UI immediately after command', c.timerUi.paused && c.timerUi.canResume && !c.timerUi.canPause);
releaseQuery(state(running));
await staleQuery;
check('Older running query cannot overwrite pause', c.timerUi.paused && c.state.timer.state === 'PAUSED');
c.toggleCurrentTimer();
await new Promise(resolve => setImmediate(resolve));
check('Toggle reads fresh snapshot and resumes', operations.at(-1) === 'resume' && !c.timerUi.paused && c.timerUi.canPause);
check('Busy flag clears after command', !c.isBusy);

const tabs = read('pages/components/AdaptedHdsTabs.ets');
const controls = read('pages/routes/focus/FocusTimerControls.ets');
const mini = read('pages/routes/focus/FocusTimerMiniBar.ets');
const MiniController = vm.runInContext(stripTypeScriptTypes('class MiniController {\n' +
  ['toggleTimer', 'reload', 'resolveDisplaySeconds'].map(name => method(name, mini)).join('\n') +
  '\n}\nMiniController;', { mode: 'transform' }), sandbox);
const m = new MiniController();
Object.assign(m, { requestGate: new models.FocusRequestGate(), state: state(running),
  timerUi: new models.FocusActiveUiState(running), busy: false });
let releaseMiniQuery;
m.repository = { state: () => new Promise(resolve => { releaseMiniQuery = resolve; }),
  command: async () => state(paused) };
const staleMiniQuery = m.reload();
await m.toggleTimer();
check('miniBar pause updates its own icon state', m.timerUi.paused && m.timerUi.canResume);
releaseMiniQuery(state(running));
await staleMiniQuery;
check('Late miniBar query cannot undo pause', m.timerUi.paused && !m.busy);
check('Dynamic Sheet contains no Builder', !sheet.includes('@Builder'));
check('Activity is a Component with explicit scalar props',
  read('pages/routes/focus/FocusActiveContent.ets').includes('@Prop paused: boolean') && sheet.includes('paused: this.timerUi.paused'));
check('Callbacks read current timer rather than Builder argument',
  sheet.includes('this.toggleCurrentTimer()') && sheet.includes('this.jointFinishCurrentTimer()'));
check('Setup uses official SegmentButtonV2', read('pages/routes/focus/FocusTimerSetup.ets').includes('TabSegmentButtonV2({'));
check('Scroll/Column are top-aligned', sheet.includes('.justifyContent(FlexAlign.Start)') && sheet.includes('.align(Alignment.Top)'));
check('Core round action has material scope', controls.includes('ImmersiveMaterialScope({') && controls.includes('MaterialPresets.buttonGlow'));
check('Material slot button independently observes icon/enabled links',
  controls.includes('FocusScopedRoundButton({ symbol: $symbol') && controls.includes('@Link actionEnabled: boolean;'));
check('Text actions have explicit capsule shape', controls.includes('Button(this.label, { type: ButtonType.Capsule })'));
check('No VERTICAL miniBar or unconditional EXPAND', !tabs.includes('HdsBarLayoutMode') && !tabs.includes('miniBarStyle: HdsBarStyle.EXPAND'));
check('miniBar style uses saved user state and explicit callbacks',
  tabs.includes('this.miniBarExpanded ? HdsBarStyle.EXPAND : HdsBarStyle.COLLAPSE') && !tabs.includes('.bind('));
check('miniBar content and host share state outside Builder evaluation',
  tabs.includes("@StorageLink('FocusMiniBarExpanded')") && mini.includes("@StorageLink('FocusMiniBarExpanded')"));
check('Only explicit title button opens Sheet', mini.includes('.onClick(this.onOpen)') && !mini.includes('.onClick(() => this.onOpen())'));
check('MiniBar uses symbol controls, single row, and dynamic system color',
  mini.includes("$r('sys.symbol.pause_fill')") && !mini.includes('Column(') && mini.includes("$r('sys.color.font_primary')"));
for (const file of ['pages/routes/main/TaskListSection.ets', 'pages/routes/main/HabitListSection.ets', 'pages/routes/focus/FocusRootBridge.ets']) {
  const source = read(file), marker = file.endsWith('FocusRootBridge.ets') ? '.bindSheet($$this.isShow' : '.bindSheet($$this.isFocusTimerShow';
  const binding = source.slice(source.indexOf(marker), source.indexOf(marker) + 460);
  check(file + ' enables native close button', binding.includes('showClose: true'));
}
const swipeAction = controls.slice(controls.indexOf('export struct FocusSwipeAction'),
  controls.indexOf('struct FocusScopedRoundButton'));
check('Right-swipe component independently observes existing theme foreground',
  swipeAction.includes("@StorageProp('currentTheme')") && swipeAction.includes('.fontColor([this.theme.bgPrimary])') &&
  swipeAction.includes('.backgroundColor(this.theme.accent)') &&
  read('pages/components/TaskItemCard.ets').includes('FocusSwipeAction({') &&
  read('pages/routes/main/HabitListSection.ets').includes('FocusSwipeAction({'));
console.log('Harmony repair checks: ' + checks + ' passed; host/source contracts, not device recomposition/rendering.');
