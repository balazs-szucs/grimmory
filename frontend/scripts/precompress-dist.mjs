import {readdir, readFile, rm, writeFile} from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {promisify} from 'node:util'
import {brotliCompress, constants, gzip} from 'node:zlib'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const frontendRoot = path.resolve(scriptDir, '..')
const distDir = path.resolve(frontendRoot, process.argv[2] ?? 'dist/grimmory/browser')

const minimumBytes = 1024
const textExtensions = new Set(['.css', '.html', '.js', '.json', '.mjs', '.svg', '.txt', '.webmanifest', '.xml'])
const compressibleExtensions = new Set([...textExtensions, '.wasm'])
const gzipAsync = promisify(gzip)
const brotliCompressAsync = promisify(brotliCompress)

async function* walk(directory) {
  for (const entry of await readdir(directory, {withFileTypes: true})) {
    const fullPath = path.join(directory, entry.name)

    if (entry.isDirectory()) {
      yield* walk(fullPath)
      continue
    }

    if (entry.isFile()) {
      yield fullPath
    }
  }
}

function isCompressible(filePath) {
  return compressibleExtensions.has(path.extname(filePath).toLowerCase())
}

function brotliModeFor(filePath) {
  return textExtensions.has(path.extname(filePath).toLowerCase())
    ? constants.BROTLI_MODE_TEXT
    : constants.BROTLI_MODE_GENERIC
}

async function writeSidecar(filePath, suffix, compressedBuffer, originalSize) {
  const sidecarPath = `${filePath}${suffix}`

  if (compressedBuffer.length >= originalSize) {
    await rm(sidecarPath, {force: true})
    return 0
  }

  await writeFile(sidecarPath, compressedBuffer)
  return compressedBuffer.length
}

const summary = {
  files: 0,
  originalBytes: 0,
  gzipBytes: 0,
  brotliBytes: 0,
}

for await (const filePath of walk(distDir)) {
  if (!isCompressible(filePath) || filePath.endsWith('.br') || filePath.endsWith('.gz')) {
    continue
  }

  const source = await readFile(filePath)
  if (source.length < minimumBytes) {
    continue
  }

  summary.files += 1
  summary.originalBytes += source.length
  const [gzipBuffer, brotliBuffer] = await Promise.all([
    gzipAsync(source, {level: 9}),
    brotliCompressAsync(source, {
      params: {
        [constants.BROTLI_PARAM_MODE]: brotliModeFor(filePath),
        [constants.BROTLI_PARAM_QUALITY]: 10,
      },
    }),
  ])

  summary.gzipBytes += await writeSidecar(filePath, '.gz', gzipBuffer, source.length)
  summary.brotliBytes += await writeSidecar(filePath, '.br', brotliBuffer, source.length)
}

console.log(`Precompressed ${summary.files} frontend assets in ${distDir}`)
console.log(`- original bytes: ${summary.originalBytes}`)
console.log(`- gzip bytes: ${summary.gzipBytes}`)
console.log(`- brotli bytes: ${summary.brotliBytes}`)