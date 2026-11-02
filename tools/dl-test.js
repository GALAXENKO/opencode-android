(async () => {
  const blob = new Blob([JSON.stringify({ hello: 'world', from: 'android' }, null, 2)], {
    type: 'application/json',
  });
  const u = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = u;
  a.download = 'oc-export-test.json';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(u);
  return 'clicked';
})()
