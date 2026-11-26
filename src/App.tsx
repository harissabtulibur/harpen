import { useMemo, useState } from 'react';
import {
  Check,
  Copy,
  Download,
  FileCode2,
  FolderTree,
  Search,
  Terminal,
} from 'lucide-react';
import { createZipBlob } from './utils/zipBuilder';

interface ProjectFile {
  path: string;
  shortPath: string;
  name: string;
  category: ModuleCategory;
  content: string;
  lines: number;
  bytes: number;
}

type ModuleCategory =
  | 'All'
  | 'Gradle & Manifest'
  | 'Drawing & Input'
  | 'Data & Format'
  | 'OCR & PDF'
  | 'Compose UI'
  | 'Tests';

type ActiveTab = 'files' | 'architecture' | 'build';

const rawModules = import.meta.glob('/harpen-android/**/*', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

function classifyCategory(shortPath: string): Exclude<ModuleCategory, 'All'> {
  if (shortPath.includes('/test/')) return 'Tests';
  if (
    shortPath.endsWith('.kts') ||
    shortPath.endsWith('.toml') ||
    shortPath.endsWith('.properties') ||
    shortPath.endsWith('.pro') ||
    shortPath.endsWith('AndroidManifest.xml') ||
    shortPath.includes('/res/') ||
    shortPath.endsWith('README.md')
  ) {
    return 'Gradle & Manifest';
  }
  if (shortPath.includes('/canvas/') || shortPath.includes('/input/')) {
    return 'Drawing & Input';
  }
  if (shortPath.includes('/ocr/') || shortPath.includes('/pdf/')) {
    return 'OCR & PDF';
  }
  if (shortPath.includes('/data/') || shortPath.includes('/model/')) {
    return 'Data & Format';
  }
  return 'Compose UI';
}

const CATEGORIES: ModuleCategory[] = [
  'All',
  'Gradle & Manifest',
  'Drawing & Input',
  'Data & Format',
  'OCR & PDF',
  'Compose UI',
  'Tests',
];

const ARCHITECTURE_ROWS = [
  {
    layer: 'Custom Native Drawing Engine',
    packagePath: 'com.harpen.studio.canvas',
    files: 'HarpenCanvasView.kt, StrokeRenderer.kt',
    summary:
      'Hardware-accelerated android.view.View with MotionEvent historical batching, pointer lifecycle isolation, Catmull-Rom smoothing, 15 pen/brush/eraser tools, templates, snap-to-grid, ruler, and lasso/rect selection.',
  },
  {
    layer: 'PalmGuard & Touch Debugger',
    packagePath: 'com.harpen.studio.input',
    files: 'PalmGuardEngine.kt, TouchDebuggerController.kt',
    summary:
      'Modes A–E input protection with safe non-locking recovery, realistic universal capacitive stylus handling, bounded circular event buffer, live color-coded canvas markers, and CSV export.',
  },
  {
    layer: 'Local Storage, Room & .harpen Format',
    packagePath: 'com.harpen.studio.data.*',
    files: 'HarpenDatabase.kt, HarpenArchiveManager.kt, SafStorageManager.kt, SettingsRepository.kt',
    summary:
      'Room library database, versioned .harpen ZIP+JSON project archive with optional AES-GCM encryption, SAF external SD/USB folder backup, and DataStore persistence across 13 settings categories.',
  },
  {
    layer: 'Offline OCR & CameraX',
    packagePath: 'com.harpen.studio.ocr',
    files: 'OfflineOcrManager.kt, IndonesianEnglishCorrector.kt, CameraScanScreen.kt',
    summary:
      'CameraX preview & capture, gallery import, contrast/grayscale enhancement, offline ML Kit Latin OCR, and Indonesian/English cleanup & spelling review preserving technical tokens.',
  },
  {
    layer: 'PDF Import & Crisp Export',
    packagePath: 'com.harpen.studio.pdf',
    files: 'PdfEngine.kt',
    summary:
      'Native PdfRenderer backdrop rendering for PDF annotation and PdfDocument multi-page vector/raster export alongside PNG, JPG, TXT, and Android Sharesheet.',
  },
  {
    layer: 'Tablet-First Jetpack Compose UI',
    packagePath: 'com.harpen.studio.ui.*',
    files: 'HomeScreen.kt, WorkspaceScreen.kt, ColorAndToolPopovers.kt, SettingsScreen.kt, TouchDebuggerScreen.kt',
    summary:
      'Adaptive portrait and landscape layouts, collapsible sidebar & floating toolbar, Distraction-Free Mode, HSV color wheel, layer panel, and centralized 13-category Settings.',
  },
];

export default function App() {
  const files = useMemo<ProjectFile[]>(() => {
    return Object.entries(rawModules)
      .map(([fullPath, content]) => {
        const shortPath = fullPath.replace(/^\/harpen-android\//, '');
        const parts = shortPath.split('/');
        return {
          path: fullPath,
          shortPath,
          name: parts[parts.length - 1],
          category: classifyCategory(shortPath),
          content,
          lines: content.split('\n').length,
          bytes: new TextEncoder().encode(content).length,
        };
      })
      .sort((a, b) => a.shortPath.localeCompare(b.shortPath));
  }, []);

  const [activeTab, setActiveTab] = useState<ActiveTab>('files');
  const [selectedCategory, setSelectedCategory] = useState<ModuleCategory>('All');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedPath, setSelectedPath] = useState<string>(
    () =>
      files.find((f) => f.shortPath.endsWith('HarpenCanvasView.kt'))?.shortPath ??
      files[0]?.shortPath ??
      ''
  );
  const [copied, setCopied] = useState(false);

  const filteredFiles = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    return files.filter((file) => {
      if (selectedCategory !== 'All' && file.category !== selectedCategory) {
        return false;
      }
      if (!q) return true;
      return (
        file.shortPath.toLowerCase().includes(q) ||
        file.content.toLowerCase().includes(q)
      );
    });
  }, [files, selectedCategory, searchQuery]);

  const activeFile = useMemo(() => {
    return (
      filteredFiles.find((f) => f.shortPath === selectedPath) ??
      files.find((f) => f.shortPath === selectedPath) ??
      filteredFiles[0] ??
      null
    );
  }, [files, filteredFiles, selectedPath]);

  const totalLines = useMemo(
    () => files.reduce((sum, f) => sum + f.lines, 0),
    [files]
  );

  const totalKb = useMemo(
    () => (files.reduce((sum, f) => sum + f.bytes, 0) / 1024).toFixed(1),
    [files]
  );

  function handleDownloadZip() {
    const blob = createZipBlob(
      files.map((f) => ({
        path: `harpen-android/${f.shortPath}`,
        content: f.content,
      }))
    );
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'HARPEN-Android-Studio.zip';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  }

  async function handleCopyFile() {
    if (!activeFile) return;
    await navigator.clipboard.writeText(activeFile.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 1800);
  }

  return (
    <div className="min-h-screen flex flex-col bg-slate-50 text-slate-900">
      {/* 3-Zone Top Bar Contract */}
      <header className="h-16 bg-white border-b border-slate-200 px-6 flex items-center justify-between gap-8 shrink-0">
        <a
          href="#top"
          onClick={(e) => {
            e.preventDefault();
            setActiveTab('files');
          }}
          className="text-lg font-bold tracking-tight text-slate-900 whitespace-nowrap shrink-0"
        >
          HARPEN
        </a>

        <nav className="flex items-center gap-6 text-sm font-medium text-slate-600">
          <button
            type="button"
            onClick={() => setActiveTab('files')}
            className={`whitespace-nowrap shrink-0 py-1 transition-colors cursor-pointer ${
              activeTab === 'files'
                ? 'text-blue-700 border-b-2 border-blue-700'
                : 'hover:text-slate-900'
            }`}
          >
            Project Files
          </button>
          <button
            type="button"
            onClick={() => setActiveTab('architecture')}
            className={`whitespace-nowrap shrink-0 py-1 transition-colors cursor-pointer ${
              activeTab === 'architecture'
                ? 'text-blue-700 border-b-2 border-blue-700'
                : 'hover:text-slate-900'
            }`}
          >
            Architecture
          </button>
          <button
            type="button"
            onClick={() => setActiveTab('build')}
            className={`whitespace-nowrap shrink-0 py-1 transition-colors cursor-pointer ${
              activeTab === 'build'
                ? 'text-blue-700 border-b-2 border-blue-700'
                : 'hover:text-slate-900'
            }`}
          >
            APK Build Guide
          </button>
        </nav>

        <div className="shrink-0">
          <button
            type="button"
            onClick={handleDownloadZip}
            className="inline-flex items-center gap-2 px-4 py-2 text-xs font-semibold text-white bg-blue-700 rounded-lg hover:bg-blue-800 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            <Download className="w-4 h-4" />
            Download Android ZIP
          </button>
        </div>
      </header>

      {/* Main Content */}
      {activeTab === 'files' && (
        <div className="flex-1 flex min-h-0 overflow-hidden">
          {/* Left File Tree Sidebar */}
          <aside className="w-80 bg-white border-r border-slate-200 flex flex-col min-h-0 shrink-0">
            <div className="p-4 border-b border-slate-200 space-y-3">
              <div className="flex items-center justify-between text-xs text-slate-500 font-mono tabular-nums">
                <span>{files.length} native files</span>
                <span>·</span>
                <span>{totalLines.toLocaleString()} lines</span>
                <span>·</span>
                <span>{totalKb} KB</span>
              </div>

              <div className="relative">
                <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
                <input
                  type="text"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder="Search Kotlin, Gradle, or XML..."
                  className="w-full pl-9 pr-3 py-2 text-xs bg-slate-50 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                />
              </div>

              <div className="flex flex-wrap gap-1">
                {CATEGORIES.map((cat) => (
                  <button
                    key={cat}
                    type="button"
                    onClick={() => setSelectedCategory(cat)}
                    className={`px-2.5 py-1 text-xs font-medium rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                      selectedCategory === cat
                        ? 'bg-slate-900 text-white'
                        : 'bg-slate-100 text-slate-600 hover:text-slate-900'
                    }`}
                  >
                    {cat}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex-1 overflow-y-auto p-2 space-y-0.5">
              {filteredFiles.length === 0 ? (
                <div className="p-6 text-center text-xs text-slate-500">
                  No project files match your filter.
                </div>
              ) : (
                filteredFiles.map((file) => {
                  const isActive = activeFile?.shortPath === file.shortPath;
                  return (
                    <button
                      key={file.shortPath}
                      type="button"
                      onClick={() => setSelectedPath(file.shortPath)}
                      className={`w-full text-left px-3 py-2 rounded-lg transition-colors cursor-pointer flex items-start gap-2.5 ${
                        isActive
                          ? 'bg-blue-50 text-blue-900'
                          : 'hover:bg-slate-100 text-slate-700'
                      }`}
                    >
                      <FileCode2
                        className={`w-4 h-4 mt-0.5 shrink-0 ${
                          isActive ? 'text-blue-700' : 'text-slate-400'
                        }`}
                      />
                      <div className="min-w-0 flex-1">
                        <div className="text-xs font-medium truncate">
                          {file.name}
                        </div>
                        <div className="text-[11px] text-slate-500 truncate font-mono">
                          {file.shortPath}
                        </div>
                      </div>
                      <span className="text-[11px] text-slate-400 font-mono tabular-nums shrink-0">
                        {file.lines}L
                      </span>
                    </button>
                  );
                })
              )}
            </div>
          </aside>

          {/* Right Source Code Inspector */}
          <main className="flex-1 flex flex-col min-w-0 bg-slate-50">
            {activeFile ? (
              <>
                <div className="bg-white border-b border-slate-200 px-6 py-3 flex items-center justify-between gap-4">
                  <div className="min-w-0">
                    <div className="text-sm font-semibold text-slate-900 truncate font-mono">
                      harpen-android/{activeFile.shortPath}
                    </div>
                    <div className="flex items-center gap-2 text-xs text-slate-500 mt-0.5 font-mono tabular-nums">
                      <span>{activeFile.category}</span>
                      <span aria-hidden="true">·</span>
                      <span>{activeFile.lines} lines</span>
                      <span aria-hidden="true">·</span>
                      <span>{(activeFile.bytes / 1024).toFixed(1)} KB</span>
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={handleCopyFile}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
                  >
                    {copied ? (
                      <>
                        <Check className="w-3.5 h-3.5 text-teal-700" />
                        Copied
                      </>
                    ) : (
                      <>
                        <Copy className="w-3.5 h-3.5" />
                        Copy File
                      </>
                    )}
                  </button>
                </div>

                <div className="flex-1 overflow-auto p-6">
                  <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
                    <table className="w-full border-collapse text-xs font-mono leading-relaxed">
                      <tbody>
                        {activeFile.content.split('\n').map((line, idx) => (
                          <tr key={idx} className="hover:bg-slate-50">
                            <td className="w-12 select-none text-right pr-4 pl-3 py-0.5 text-slate-400 border-r border-slate-100 tabular-nums align-top">
                              {idx + 1}
                            </td>
                            <td className="pl-4 pr-6 py-0.5 whitespace-pre text-slate-800">
                              {line || ' '}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              </>
            ) : (
              <div className="flex-1 flex items-center justify-center text-sm text-slate-500">
                Select a file from the left tree to inspect its source.
              </div>
            )}
          </main>
        </div>
      )}

      {activeTab === 'architecture' && (
        <main className="flex-1 overflow-y-auto p-8">
          <div className="max-w-5xl mx-auto space-y-6">
            <div>
              <h1 className="text-2xl font-bold text-slate-900 text-balance">
                HARPEN Native Android Architecture
              </h1>
              <p className="text-sm text-slate-600 mt-1 max-w-3xl">
                Generated at <code className="font-mono text-slate-800">/harpen-android</code> as a native Kotlin, Jetpack Compose, and custom Android View project targeting Android 8.0–15 tablets with 4 GB RAM and universal capacitive styluses.
              </p>
            </div>

            <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
              <table className="w-full text-left border-collapse">
                <thead>
                  <tr className="border-b border-slate-200 bg-slate-50 text-xs font-semibold text-slate-600">
                    <th className="py-3 px-4">Subsystem</th>
                    <th className="py-3 px-4">Package</th>
                    <th className="py-3 px-4">Key Source Files</th>
                    <th className="py-3 px-4">Implementation Scope</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-200 text-sm">
                  {ARCHITECTURE_ROWS.map((row) => (
                    <tr key={row.layer} className="hover:bg-slate-50 align-top">
                      <td className="py-3.5 px-4 font-medium text-slate-900 whitespace-nowrap">
                        {row.layer}
                      </td>
                      <td className="py-3.5 px-4 font-mono text-xs text-blue-700 whitespace-nowrap">
                        {row.packagePath}
                      </td>
                      <td className="py-3.5 px-4 font-mono text-xs text-slate-600">
                        {row.files}
                      </td>
                      <td className="py-3.5 px-4 text-xs text-slate-600 leading-relaxed">
                        {row.summary}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </main>
      )}

      {activeTab === 'build' && (
        <main className="flex-1 overflow-y-auto p-8">
          <div className="max-w-4xl mx-auto space-y-6">
            <div>
              <h1 className="text-2xl font-bold text-slate-900 text-balance">
                Building the HARPEN APK in Android Studio
              </h1>
              <p className="text-sm text-slate-600 mt-1">
                This Node.js container does not include the Android SDK, JDK 17, or Gradle toolchain, so no APK binary has been compiled here. Use the steps below to build and test on your tablet.
              </p>
            </div>

            <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-4">
              <div className="flex items-center gap-2 text-sm font-semibold text-slate-900">
                <FolderTree className="w-4 h-4 text-blue-700" />
                <span>01. Android Studio GUI Workflow</span>
              </div>
              <ol className="list-decimal list-inside space-y-2 text-sm text-slate-700">
                <li>
                  Click <strong>Download Android ZIP</strong> in the top bar and extract{' '}
                  <code className="font-mono text-xs bg-slate-100 px-1.5 py-0.5 rounded">harpen-android/</code>.
                </li>
                <li>
                  Open <strong>Android Studio Ladybug (2024.2.1+)</strong> with <strong>JDK 17</strong> and <strong>Android SDK Platform 35</strong> installed.
                </li>
                <li>
                  Select <strong>File → Open</strong> and choose the extracted{' '}
                  <code className="font-mono text-xs bg-slate-100 px-1.5 py-0.5 rounded">harpen-android</code> directory.
                </li>
                <li>
                  Allow Gradle sync to download dependencies declared in{' '}
                  <code className="font-mono text-xs bg-slate-100 px-1.5 py-0.5 rounded">gradle/libs.versions.toml</code>.
                </li>
                <li>
                  Choose <strong>Build → Build Bundle(s) / APK(s) → Build APK(s)</strong> to produce{' '}
                  <code className="font-mono text-xs bg-slate-100 px-1.5 py-0.5 rounded">app/build/outputs/apk/debug/app-debug.apk</code>.
                </li>
              </ol>
            </div>

            <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-3">
              <div className="flex items-center gap-2 text-sm font-semibold text-slate-900">
                <Terminal className="w-4 h-4 text-teal-700" />
                <span>02. Command-Line Gradle Build</span>
              </div>
              <pre className="bg-slate-900 text-slate-100 rounded-lg p-4 text-xs font-mono overflow-x-auto leading-relaxed">
{`cd harpen-android
gradle wrapper --gradle-version 8.10.2
./gradlew assembleDebug
./gradlew testDebugUnitTest`}
              </pre>
            </div>
          </div>
        </main>
      )}
    </div>
  );
}
