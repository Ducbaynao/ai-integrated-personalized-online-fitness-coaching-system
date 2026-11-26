const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const mobile=path.resolve(process.argv[2]),req=require('module').createRequire(path.join(mobile,'package.json'));
const ts=req('typescript'),React=req('react'),renderer=req('react-test-renderer');global.IS_REACT_ACT_ENVIRONMENT=true;
const rn={StyleSheet:{create:x=>x},Platform:{OS:'android'},useColorScheme:()=> 'light',Alert:{alert:()=>{}}};
for(const n of ['ActivityIndicator','Modal','Pressable','ScrollView','Text','TextInput','View','RefreshControl'])rn[n]=n;
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}};
let currentGet=async()=>null,versionSend=async()=>null;const calls=[];
const api={getCurrentGoal:(...args)=>currentGet(...args),createGoalVersion:(...args)=>{calls.push(args);return versionSend(...args)}};
let activeFocusCleanup=null,activeFocusCallback=null;
const cache=new Map();
function load(file){file=path.resolve(file);if(!fs.existsSync(file))file=['.ts','.tsx','.js'].map(e=>file+e).find(fs.existsSync);if(!file)throw Error('Missing module');if(cache.has(file))return cache.get(file).exports;
 const m={exports:{}};cache.set(file,m);const js=ts.transpileModule(fs.readFileSync(file,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.React}}).outputText;
 const local=n=>{if(n==='react-native')return rn;if(n==='react-native-safe-area-context')return {SafeAreaView:'SafeAreaView'};
 if(n==='expo-router')return {useRouter:()=>({push:()=>{},back:()=>{},canGoBack:()=>true}),useFocusEffect:cb=>React.useEffect(()=>{activeFocusCallback=cb;activeFocusCleanup=cb();return ()=>{if(typeof activeFocusCleanup==='function')activeFocusCleanup();activeFocusCleanup=null}},[cb])};
 if(n==='@/services/fitnessGoalApi')return {fitnessGoalApi:api};
 if(n==='@/services/apiClient')return {ApiError:load(path.join(mobile,'src/types/auth.ts')).ApiError};
 if(/components\/(GoalOverviewTab|GoalTargetsTab|GoalHistoryTab|GoalProposalsTab|PauseResumeGoalModal|GoalScreenSkeleton)$/.test(n)){const key=n.split('/').pop();return {[key]:()=>null};}
 return n.startsWith('@/')?load(path.join(mobile,'src',n.slice(2))):n.startsWith('.')?load(path.resolve(path.dirname(file),n)):req(n)};
 vm.runInThisContext('(function(require,module,exports){'+js+'\n})',{filename:file})(local,m,m.exports);return m.exports;
}
const {CreateGoalVersionModal:Modal}=load(path.join(mobile,'src/features/goal/components/CreateGoalVersionModal.tsx'));
const {CurrentGoalScreen:Screen}=load(path.join(mobile,'src/features/goal/CurrentGoalScreen.tsx'));
const act=renderer.act;let passed=0,failed=0;async function check(name,fn){try{await fn();passed++;console.log('PASS '+name)}catch(e){failed++;console.log('FAIL '+name+': '+e.message)}}
const baseProps={visible:true,currentTitle:'Goal A',isSubmitting:false,isDark:false,sessionId:1,onClose:()=>{}};
async function mount(code,submit){let tree;await act(async()=>{tree=renderer.create(React.createElement(Modal,{...baseProps,currentGoalTypeCode:code,onSubmit:submit}))});return tree;}
async function fill(tree,text='Draft A'){await act(async()=>{tree.root.findByProps({testID:'goal-version-reason-input'}).props.onChangeText(text)})}
async function submit(tree){await act(async()=>{await tree.root.findByProps({testID:'goal-version-submit-button'}).props.onPress()})}
const goal=(id,code='MUSCLE_GAIN')=>({id,studentId:'s1',title:'Title '+id,status:'ACTIVE',createdBy:'s1',currentVersion:{id:'version-'+id,versionNumber:1,title:'Title '+id,startDate:'2026-11-26',durationDays:60,objectives:[{goalTypeId:1,goalTypeCode:code,priority:'PRIMARY',sortOrder:0}],targets:[]}});
const {CreateGoalTransitionModal:Transition}=load(path.join(mobile,'src/features/goal/components/CreateGoalTransitionModal.tsx'));
api.createGoalTransition=(...args)=>{calls.push(args);return versionSend(...args)};api.getGoal=(id)=>currentGet(id);
const ApiError=load(path.join(mobile,'src/types/auth.ts')).ApiError;
const cfg=t=>t==='version'?{Component:Modal,open:'adjust-goal-version-button',root:'create-goal-version-modal',reason:'goal-version-reason-input',title:'goal-version-title-input',submit:'goal-version-submit-button',cancel:'goal-version-cancel-button',banner:'goal-version-error-banner'}:{Component:Transition,open:'transition-goal-button',root:'create-goal-transition-modal',reason:'goal-transition-reason-input',title:'goal-transition-title-input',submit:'goal-transition-submit-button',cancel:'goal-transition-cancel-button',banner:'goal-transition-error-banner'};
async function start(){let tree;await act(async()=>tree=renderer.create(React.createElement(Screen)));return tree;}
async function open(tree,c){await act(async()=>tree.root.findByProps({testID:c.open}).props.onPress())}
async function draft(tree,c,text){await act(async()=>{tree.root.findByProps({testID:c.reason}).props.onChangeText(text);tree.root.findByProps({testID:c.title}).props.onChangeText(text+' title')})}
async function press(tree,id){await act(async()=>{tree.root.findByProps({testID:id}).props.onPress()})}
async function refresh(tree){await act(async()=>{tree.root.findAllByType('ScrollView').find(n=>n.props.refreshControl).props.refreshControl.props.onRefresh()})}
function payload(t,text){return t==='version'?{title:text+' title',changeReason:text,goalTypeCode:'MUSCLE_GAIN'}:{title:text+' title',transitionReason:text,goalTypeCode:'STRENGTH'}}
(async()=>{
for(const target of ['version','transition']){
 const c=cfg(target);
 await check(target+' A blocked at UI+parent; fresh B succeeds',async()=>{const pending=deferred();let gets=0;currentGet=()=>++gets===1?Promise.resolve(goal('A')):gets===2?pending.promise:Promise.resolve(goal('B'));versionSend=async()=>goal('B');calls.length=0;const tree=await start();try{
 await refresh(tree);assert.equal(gets,2);await open(tree,c);await draft(tree,c,'draft-A');await act(async()=>pending.resolve(goal('B')));
 assert.equal(tree.root.findByProps({testID:c.reason}).props.value,'draft-A');assert(tree.root.findByProps({testID:c.banner}));assert.equal(tree.root.findByProps({testID:c.submit}).props.disabled,true);
 await press(tree,c.submit);assert.equal(calls.length,0);
 let rejected=false;await act(async()=>{try{await tree.root.findByType(c.Component).props.onSubmit(payload(target,'draft-A'))}catch(e){rejected=true}});assert(rejected,'Parent must reject even bypassing disabled child');assert.equal(calls.length,0,'Neither A nor B mutated');
 await press(tree,c.cancel);await open(tree,c);assert.equal(tree.root.findByProps({testID:c.reason}).props.value,'');await draft(tree,c,'draft-B');await press(tree,c.submit);assert.equal(calls.length,1);assert.equal(calls[0][0],'B');assert.equal(calls[0][1].title,'draft-B title');assert.equal(calls[0][1][target==='version'?'changeReason':'transitionReason'],'draft-B');
 }finally{await act(async()=>tree.unmount())}});
 await check(target+' same goal refresh retains draft and target',async()=>{const pending=deferred();let gets=0;currentGet=()=>++gets===1?Promise.resolve(goal('A')):gets===2?pending.promise:Promise.resolve(goal('A'));versionSend=async()=>goal('A');calls.length=0;const tree=await start();try{
 await refresh(tree);await open(tree,c);await draft(tree,c,'same-A');await act(async()=>pending.resolve({...goal('A'),title:'Updated A'}));assert.equal(tree.root.findByProps({testID:c.reason}).props.value,'same-A');assert.equal(tree.root.findByProps({testID:c.submit}).props.disabled,false);await press(tree,c.submit);assert.equal(calls.length,1);assert.equal(calls[0][0],'A');assert.equal(calls[0][1].title,'same-A title');
 }finally{await act(async()=>tree.unmount())}});
 await check(target+' stale completion and reconciliation error/retry preserve new editor',async()=>{const mutation=deferred(),sync=deferred(),retry=deferred();let gets=0;currentGet=()=>++gets===1?Promise.resolve(goal('A')):gets===2?sync.promise:retry.promise;versionSend=()=>mutation.promise;calls.length=0;const tree=await start();try{
 await open(tree,c);await draft(tree,c,'old-A');await press(tree,c.submit);assert.equal(calls.length,1);
 await act(async()=>{tree.root.findByType(c.Component).props.onClose()});await open(tree,c);await draft(tree,c,'new-A');await act(async()=>mutation.resolve(goal('A')));assert.equal(gets,2);await act(async()=>sync.reject(new ApiError(0,'network error')));
 assert(tree.root.findByProps({testID:'reconciliation-retry-banner'}));assert.equal(tree.root.findByProps({testID:c.root}).props.visible,true);assert.equal(tree.root.findByProps({testID:c.reason}).props.value,'new-A');assert.equal(tree.root.findByProps({testID:c.submit}).props.disabled,false);assert.equal(calls.length,1);
 await press(tree,'retry-reconciliation-button');assert.equal(gets,3);await act(async()=>retry.resolve(goal('A')));assert.equal(tree.root.findAllByProps({testID:'reconciliation-retry-banner'}).length,0);assert.equal(tree.root.findByProps({testID:c.reason}).props.value,'new-A');assert.equal(calls.length,1,'GET retry must not resend mutation');
 }finally{await act(async()=>tree.unmount())}});

 await check(target+' focus cleanup rejects obsolete callback and opens fresh context',async()=>{let next=goal('A');currentGet=async()=>next;versionSend=async()=>next;calls.length=0;const tree=await start();try{await open(tree,c);await draft(tree,c,'old-A');const obsolete=tree.root.findByType(c.Component).props.onSubmit;
 await act(async()=>{activeFocusCleanup();activeFocusCleanup=null});let blocked=false;await act(async()=>{try{await obsolete(payload(target,'old-A'))}catch(e){blocked=true}});assert(blocked);assert.equal(calls.length,0);
 next=goal('B');await act(async()=>{activeFocusCleanup=activeFocusCallback()});await open(tree,c);await draft(tree,c,'focus-B');await press(tree,c.submit);assert.equal(calls.length,1);assert.equal(calls[0][0],'B');assert.equal(calls[0][1].title,'focus-B title');
 }finally{await act(async()=>tree.unmount())}});
 await check(target+' route cleanup rejects obsolete parent callback',async()=>{currentGet=async id=>goal(id||'A');versionSend=async()=>goal('B');calls.length=0;const tree=await start();try{await open(tree,c);const obsolete=tree.root.findByType(c.Component).props.onSubmit;await act(async()=>tree.update(React.createElement(Screen,{goalId:'B'})));let blocked=false;await act(async()=>{try{await obsolete(payload(target,'old-A'))}catch(e){blocked=true}});assert(blocked);assert.equal(calls.length,0);assert.equal(tree.root.findByProps({testID:c.root}).props.visible,false)}finally{await act(async()=>tree.unmount())}});
}
console.log('Independent result: '+passed+' PASS, '+failed+' FAIL');process.exitCode=failed?1:0;
})().catch(e=>{console.error(e);process.exitCode=2});
