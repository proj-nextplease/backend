import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = process.cwd();
const readmePath = join(root, 'README.md');
const start = '<!-- PROJECT_STRUCTURE_START -->';
const end = '<!-- PROJECT_STRUCTURE_END -->';
const ignored = new Set(['.git', 'target', '.mvn', '.DS_Store', '.env']);

function walk(dir, depth = 0) {
  if (depth > 8) {
    return [];
  }

  return readdirSync(dir)
    .filter((name) => !ignored.has(name))
    .sort((a, b) => a.localeCompare(b))
    .flatMap((name) => {
      const fullPath = join(dir, name);
      const relPath = relative(root, fullPath);
      const isDirectory = statSync(fullPath).isDirectory();
      const line = `${'  '.repeat(depth)}- ${relPath}${isDirectory ? '/' : ''}`;
      return isDirectory ? [line, ...walk(fullPath, depth + 1)] : [line];
    });
}

const structure = [
  start,
  '```text',
  '.',
  ...walk(root).map((line) => line.replace(/^- /, '+- ')),
  '```',
  end,
].join('\n');

const existing = readFileSync(readmePath, 'utf8');
const next = existing.includes(start) && existing.includes(end)
  ? existing.replace(new RegExp(`${start}[\\s\\S]*${end}`), structure)
  : `${existing.trim()}\n\n## Project Structure\n\n${structure}\n`;

writeFileSync(readmePath, `${next.trim()}\n`);
