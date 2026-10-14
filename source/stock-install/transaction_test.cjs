const fs = require('fs');
const path = require('path');
(async () => {
 const {instance} = await WebAssembly.instantiate(fs.readFileSync(path.join(__dirname,'transaction_test.wasm')));
 const code = instance.exports.transaction_self_test();
 if(code) throw new Error(`Actual C transaction fault check failed: ${code}`);
 console.log('PASS: 8 actual C transaction ordering/failure/recovery checks.');
})().catch(e=>{console.error(e);process.exitCode=1;});
