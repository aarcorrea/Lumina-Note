import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const rootDir = path.resolve(__dirname, '..');
const distDir = path.join(rootDir, 'dist');
const targetAssetsDir = path.join(rootDir, 'mobile', 'android', 'app', 'src', 'main', 'assets', 'public');

console.log('[Android Sync] Synchronizing web bundle to Android assets...');

if (!fs.existsSync(distDir)) {
  console.error('[Android Sync] Error: dist/ directory not found. Please run "npm run build:vite" first.');
  process.exit(1);
}

// Clean target directory
if (fs.existsSync(targetAssetsDir)) {
  fs.rmSync(targetAssetsDir, { recursive: true, force: true });
}
fs.mkdirSync(targetAssetsDir, { recursive: true });

// Copy dist to Android assets
function copyRecursive(src, dest) {
  const stat = fs.statSync(src);
  if (stat.isDirectory()) {
    fs.mkdirSync(dest, { recursive: true });
    for (const file of fs.readdirSync(src)) {
      copyRecursive(path.join(src, file), path.join(dest, file));
    }
  } else {
    fs.copyFileSync(src, dest);
  }
}

copyRecursive(distDir, targetAssetsDir);

const files = fs.readdirSync(targetAssetsDir);
console.log(`[Android Sync] Successfully copied web assets to Android assets (${files.length} root items).`);
