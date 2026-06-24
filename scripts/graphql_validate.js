#!/usr/bin/env node
// GraphQL SDL gate validator. If the `graphql` npm package resolves (locally or globally), parse +
// validate the schema for real with buildSchema (throws on a malformed schema). Otherwise fall back to
// a structural check: a definition keyword is present and braces balance. Usage: node graphql_validate.js <file>
const fs = require('fs');
const src = fs.readFileSync(process.argv[2], 'utf8');

function structural() {
  if (!/\b(type|interface|input|enum|union|scalar|schema)\b/.test(src)) throw new Error('no SDL definition');
  if ((src.match(/{/g) || []).length !== (src.match(/}/g) || []).length) throw new Error('unbalanced braces');
  console.log('structural ok (definition present, braces balanced)');
}

let graphql;
try { graphql = require('graphql'); }
catch (_) {
  try {
    const root = require('child_process').execSync('npm root -g').toString().trim();
    graphql = require(root + '/graphql');
  } catch (_2) { graphql = null; }
}

if (graphql) {
  try { graphql.buildSchema(src); console.log('graphql buildSchema ok'); }
  catch (e) { console.error(String(e)); process.exit(1); }
} else {
  try { structural(); } catch (e) { console.error(String(e)); process.exit(1); }
}
