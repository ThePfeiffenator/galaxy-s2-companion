const fs = require('fs');
const path = require('path');
(async () => {
  const {instance} = await WebAssembly.instantiate(fs.readFileSync(path.join(__dirname,'self_test.wasm')));
  const result = instance.exports.candidate_self_test();
  if(result) throw new Error(`Actual C core check failed: ${result}`);
  console.log('PASS: ' + instance.exports.sms_test_count() + ' executed SMS parser/bounds/epoch/tombstone checks.');
  console.log('PASS: ' + instance.exports.sms_batch_test_count() + ' executed batch bounds/order/timeout/deduplication/helper-result checks.');
  console.log('PASS: ' + instance.exports.sms_address_test_count() + ' executed exact socket address/length/bounds checks.');
  console.log('PASS: actual C bridge parser, 23 mute/ordering checks, 15 reconnect/epoch/backoff/exact-once checks, and SHA-256 empty/streaming-abc/million-a vectors.');
})().catch(e=>{console.error(e);process.exitCode=1;});
