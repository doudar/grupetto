// Provisional two-node replay, not a calibrated physical identification.
const fs = require('fs');
const root = process.argv[2];
if (!root) throw new Error('Usage: node tools/flywheel_thermal_replay.cjs <local-recording-directory>');
const a = JSON.parse(fs.readFileSync(root + '/top_scale_verified_readings.json'));
const b = JSON.parse(fs.readFileSync(root + '/cooldown_2030/additional_effort_verified_readings.json'));
const obs = [...a, ...b];
const c = f => (f - 32) / 1.8;
const times = obs.map(x => Date.parse(x.estimate_time_local) / 1000);
const blocks = [[5,40],[40,55],[55,60],[60,75]];
const udp=[];
for(const name of ['smartbench-udp-20261107-1927.jsonl','smartbench-cooldown-udp-20261107-2030.jsonl']) {
  for(const line of fs.readFileSync(root+'/'+name,'utf8').trim().split('\n')) {
    const r=JSON.parse(line), m=r.message?.match(/\(Main\): W=(\d+)/);
    if(m)udp.push([Date.parse(r.time)/1000,+m[1]]);
  }
}
function inputs(ambient=69, mode='display') {
 return obs.slice(1).map((x,j)=>{
  const dt=times[j+1]-times[j], e=Math.exp(-dt/1000);
  let p=8*((c(x.grupetto_estimated_f)-c(ambient))-e*(c(obs[j].grupetto_estimated_f)-c(ambient)))/(1-e);
  let fallback=false;
  if(mode==='udp'){
   const rows=udp.filter(r=>r[0]>=times[j]&&r[0]<times[j+1]);
   if(rows.length>=dt*.5)p=rows.reduce((s,r)=>s+r[1],0)/rows.length;
   else fallback=true;
  }
  return {dt,p,fallback};
 });
}
function replay(v, ambient=69, mode='display', inputScale=1, step=2){
 const [cd,ch,g,hd,hh]=v;let d=c(69)-c(ambient),h=d;
 const out=[69];
 for(const q of inputs(ambient,mode)){
  const p=q.p*inputScale, n=Math.ceil(q.dt/step),dt=q.dt/n;
  if(!ch)d=d*Math.exp(-q.dt*hd/cd)+p/hd*(-Math.expm1(-q.dt*hd/cd));
  else for(let k=0;k<n;k++){
   const dd=(p-hd*d-g*(d-h))/cd,dh=(g*(d-h)-hh*h)/ch;
   const dm=d+dd*dt/2,hm=h+dh*dt/2;
   d+=dt*(p-hd*dm-g*(dm-hm))/cd;h+=dt*(g*(dm-hm)-hh*hm)/ch;
  }
  out.push((d+c(ambient))*1.8+32);
 }
 return out;
}
function metrics(out){return blocks.map(([lo,hi])=>{
 const e=out.slice(lo,hi).map((t,j)=>t-obs[lo+j].accuheat_top_scale_f);
 return {n:e.length,rmseF:Math.sqrt(e.reduce((s,x)=>s+x*x,0)/e.length),biasF:e.reduce((s,x)=>s+x,0)/e.length};
});}
const proposed=[2000,6000,8,4,4];
const grid=[];
for(const cd of [1000,1500,2000,2500,3000,3500,4000,4500,5000,6000])for(const g of [2,4,6,8,12,16,24,32])for(const hd of [2,3,4,5,6,7,8]) {
 const v=[cd,8000-cd,g,hd,8-hd];grid.push({v,metrics:metrics(replay(v))});
}
grid.sort((x,y)=>x.metrics[0].rmseF-y.metrics[0].rmseF);
const variants=[['8000/8',[8000,0,0,8,0]],['6400/8',[6400,0,0,8,0]],['proposed',proposed],['train minimum',grid[0].v]];
for(const cd of [1600,2400,3200])variants.push(['Cd sensitivity',[cd,8000-cd,8,4,4]]);
for(const g of [4,6,10,12,16])variants.push(['G sensitivity',[2000,6000,g,4,4]]);
for(const hd of [3,5])variants.push(['loss split sensitivity',[2000,6000,8,hd,8-hd]]);
const result={
 assumptions:['Ambient 69 F, both initial states 69 F; no temperature state resets.', 'Fit only original samples6-40; blocked holdout41-55,56-60,additional1-15.', 'Input inferred from rounded displayed old model by exact constant-input inversion per interval. Not independent power validation. Camera timestamps differ up to seconds.', 'UDP check uses mean Main W per interval where count >= half interval seconds, display-inferred fallback otherwise. UDP not verified raw bike input.', 'Long original-to-additional gap bridged by average display-inferred input; actual within-gap chronology unknown.', 'No zero-input cooling tail. IR emissivity/ambient unverified. Rim proxy is scene maximum after first5 excluded, not bulk temperature. Center spot not identified hub temperature.', 'No physical masses identified; fitted C and conductances are effective quantities.'],
 parameterOrder:['Cd J/K','Ch J/K','G W/K','Hd W/K','Hh W/K'],blocks,
 variants:variants.map(([name,v])=>({name,v,display:metrics(replay(v)),udpProxy:metrics(replay(v,69,'udp'))})),
 ambientSensitivity:[65,69,72,75].map(ambient=>({ambient,metrics:metrics(replay(proposed,ambient))})),
 inputSensitivity:[.9,1,1.1].map(scale=>({scale,metrics:metrics(replay(proposed,69,'display',scale))})),
 gridBest:grid.slice(0,20),udpFallbackIntervals:inputs(69,'udp').map((x,i)=>x.fallback?i+1:null).filter(x=>x!==null),
 numericMaxDeltaF:Math.max(...replay(proposed,69,'display',1,2).map((x,i)=>Math.abs(x-replay(proposed,69,'display',1,.2)[i])))
};
fs.writeFileSync(root+'/two_node_analysis_results.json',JSON.stringify(result,null,2));
console.log(JSON.stringify({...result,gridBest:result.gridBest.slice(0,3)},null,2));
