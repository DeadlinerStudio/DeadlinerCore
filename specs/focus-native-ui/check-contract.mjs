import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const [iosRoot, harmonyRoot] = process.argv.slice(2);
assert.ok(iosRoot && harmonyRoot, 'Pass iOS and Harmony repository roots.');
const ios = (path) => readFileSync(join(iosRoot, path), 'utf8');
const oh = (path) => readFileSync(join(harmonyRoot, 'entry/src/main/ets', path), 'utf8');
let passed = 0;
function check(label, condition) {
  assert.ok(condition, label);
  passed += 1;
  console.log(`PASS ${label}`);
}

const home = ios('Deadliner/Features/Home/HomeView.swift');
const task = ios('Deadliner/Features/Home/Components/DDLItemCard.swift');
const focus = ios('Deadliner/Features/Focus/FocusTimerView.swift');
const components = ios('Deadliner/Features/Focus/FocusTimerComponents.swift');
check('iOS has no independent top-bar focus entry', !home.includes('focus.management.open'));
check('iOS task detail has no start entry', !ios('Deadliner/Features/Home/Sheets/TaskDetailSheet.swift').includes('FocusTimerView'));
check('iOS focus and quantity confirmation have no toolbar actions', !focus.includes('.toolbar'));
check('iOS Task/Habit reveal explicit leading swipe entries',
  task.includes('focus.entry.task') && task.includes('.leading, allowsFullSwipe: !selectionMode') &&
  home.includes('focus.entry.habit.') && home.includes('.leading, allowsFullSwipe: false'));
const taskLeading = task.slice(task.indexOf('.swipeActions(edge: .leading'), task.indexOf('if selectionMode && selected'));
const activeTaskActions = taskLeading.slice(taskLeading.indexOf('} else {'));
check('iOS active Task preserves completion-first full swipe and focus in the middle',
  taskLeading.includes('allowsFullSwipe: !selectionMode') &&
  ['onComplete()', 'Button(action: onFocus)', 'onGiveUp?()'].every(action => activeTaskActions.includes(action)) &&
  activeTaskActions.indexOf('onComplete()') < activeTaskActions.indexOf('Button(action: onFocus)') &&
  activeTaskActions.indexOf('Button(action: onFocus)') < activeTaskActions.indexOf('onGiveUp?()') &&
  !taskLeading.slice(0, taskLeading.indexOf('} else {')).includes('focus.entry.task'));
check('iOS keeps native glass with availability fallback',
  components.includes('#available(iOS 26.0, *)') && components.includes('.glassEffect') && components.includes('secondarySystemGroupedBackground'));
check('iOS separates setup and timer/result with bounded history',
  focus.includes('if !enteredTimer && timer == nil') && focus.includes('resultContent') && focus.includes('sessions.prefix(5)'));

const sheet = oh('pages/routes/focus/FocusTimerSheet.ets');
const list = oh('pages/routes/main/TaskListSection.ets');
const habit = oh('pages/routes/main/HabitListSection.ets');
const card = oh('pages/components/TaskItemCard.ets');
check('Harmony task detail has no start entry', !oh('pages/routes/main/modify/task/TaskDetailSheet.ets').includes('FocusTimerSheet'));
check('Harmony focus title bar has no actions', !sheet.slice(sheet.indexOf('.titleBar({')).includes('subIcon'));
check('Harmony Task/Habit keep bound list entries',
  list.includes('onFocusAction: item.taskState === TaskState.ACTIVE') && list.includes('initialTask: this.focusedTask') &&
  habit.includes('.swipeAction(') && habit.includes('initialHabit: this.focusedHabit'));
check('Harmony task shelf reserves three buttons only on leading swipe',
  card.includes('this.offsetX > 0 && this.onFocusAction !== undefined ? 168 : 112') && card.includes('FocusSwipeAction({'));
// Updated by focus-harmony-repair: native close is explicitly required by the latest user feedback.
check('Harmony native focus sheets restore their close action',
  list.slice(list.indexOf('.bindSheet($$this.isFocusTimerShow')).includes('showClose: true') &&
  habit.slice(habit.indexOf('.bindSheet($$this.isFocusTimerShow')).includes('showClose: true') &&
  oh('pages/routes/focus/FocusRootBridge.ets').includes('showClose: true'));
check('Harmony uses native ring and project system material',
  oh('pages/routes/focus/FocusTimerControls.ets').includes('type: ProgressType.Ring') &&
  oh('pages/routes/focus/FocusTimerControls.ets').includes('MaterialPresets.buttonGlow('));
check('Harmony separates setup and result with bounded history',
  sheet.includes('else if (!this.enteredTimer)') && sheet.includes('FocusResultContent({') && sheet.includes('.slice(0, 5)'));

console.log(`Native UI source contracts: ${passed} passed (not device rendering tests).`);
