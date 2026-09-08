import { Component, ElementRef, effect, inject, signal, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Chart, registerables, type ChartConfiguration } from 'chart.js';

// Chart.js v4 ships tree-shakeable: nothing is registered by default, and an unregistered
// controller fails at construction with "line is not a registered controller" rather than
// at import. Registering everything once at module scope is the pragmatic choice for a
// demo dashboard -- the bundle cost is a few tens of KB and the failure mode is nasty.
Chart.register(...registerables);

/**
 * Local mirrors of the platform's payloads, hand-written for the same reason
 * ContractAgent's are: contract-platform's own API is not part of the contract this
 * platform governs, so generating a client for it from its own spec would be a
 * circularity that confuses more than it saves.
 */
interface SpecVersion {
  commit: string;
  storedAt: string;
  sizeBytes: number;
  endpointCount: number;
  schemaCount: number;
  baseline: boolean;
}

interface AppSummary {
  appName: string;
  team: string;
  repo: string;
  clientVersion: string;
  usageRows: number;
  screens: string[];
  totalCallsPerDay: number;
}

interface TrafficRow {
  appName: string;
  team: string;
  location: string;
  callsPerDay: number;
  lastCalledAt: string | null;
}

interface AuditEntry {
  id: number;
  requestText: string;
  requester: string;
  targetSchema: string;
  fieldName: string;
  javaType: string;
  status: string;
  classifiedSeverity: string;
  effectiveSeverity: string;
  prUrl: string | null;
  createdAt: string;
  decidedAt: string | null;
}

const PLATFORM = 'http://localhost:8081';
const SPEC_REPO = 'orders-backend';

const INK = '#1b1b2f';
const BLUE = '#2563eb';
const VIOLET = '#7c3aed';
const TEAL = '#0d9488';

@Component({
  selector: 'app-dashboard',
  imports: [],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.css',
})
export class Dashboard {
  private http = inject(HttpClient);

  // Signals, not plain fields: Angular 21 is zoneless by default, so a write inside
  // .subscribe() is only guaranteed to repaint when it goes through a signal. Same
  // lesson as OrderDetail on Day 04 and ContractAgent on Day 07.
  specHistory = signal<SpecVersion[]>([]);
  apps = signal<AppSummary[]>([]);
  traffic = signal<TrafficRow[]>([]);
  agentLog = signal<AuditEntry[]>([]);
  error = signal<string | null>(null);

  private specCanvas = viewChild<ElementRef<HTMLCanvasElement>>('specCanvas');
  private consumerCanvas = viewChild<ElementRef<HTMLCanvasElement>>('consumerCanvas');
  private trafficCanvas = viewChild<ElementRef<HTMLCanvasElement>>('trafficCanvas');

  private charts = new Map<string, Chart>();

  constructor() {
    this.load();

    // Signal-based view queries are reactive: the canvases live inside @if blocks, so they
    // do not exist on first render and these effects re-run when they appear. Each effect
    // reads its data signal AND its canvas signal synchronously, which is what registers
    // both as dependencies.
    effect(() => this.drawSpecTimeline());
    effect(() => this.drawConsumers());
    effect(() => this.drawTraffic());
  }

  // Every subscribe carries an error handler. app.spec.ts instantiates App -- and therefore
  // this component -- with a real HttpClient and no backend running, so an unhandled error
  // here surfaces as a failing root test that looks unrelated to this file.
  private load(): void {
    this.http.get<SpecVersion[]>(`${PLATFORM}/specs/${SPEC_REPO}/history?limit=25`).subscribe({
      next: (v) => this.specHistory.set(v),
      error: () => this.error.set(
        'Cannot reach contract-platform on 8081. Is it running, and does DashboardCorsConfig map /specs/*/history?'),
    });

    this.http.get<AppSummary[]>(`${PLATFORM}/registry/apps`).subscribe({
      next: (a) => this.apps.set(a),
      error: () => this.error.set('Could not load the client registry from contract-platform on 8081.'),
    });

    this.http.get<TrafficRow[]>(`${PLATFORM}/registry/traffic`).subscribe({
      next: (t) => this.traffic.set(t),
      error: () => { /* the registry error above already says the platform is unreachable */ },
    });

    this.http.get<AuditEntry[]>(`${PLATFORM}/agent/requests`).subscribe({
      next: (r) => this.agentLog.set(r),
      error: () => { /* as above */ },
    });
  }

  // ---- derived values for the summary tiles -------------------------------

  currentEndpoints = () => this.specHistory().at(-1)?.endpointCount ?? 0;
  currentSchemas = () => this.specHistory().at(-1)?.schemaCount ?? 0;
  versionCount = () => this.specHistory().length;
  totalCalls = () => this.traffic().reduce((sum, t) => sum + t.callsPerDay, 0);
  prCount = () => this.agentLog().filter((r) => !!r.prUrl).length;

  baselineCommit(): string {
    const b = this.specHistory().find((v) => v.baseline);
    return b ? b.commit.slice(0, 7) : 'none published';
  }

  shortCommit(commit: string): string {
    return commit.slice(0, 7);
  }

  // ---- chart rendering ----------------------------------------------------

  private draw(key: string, canvas: HTMLCanvasElement, config: ChartConfiguration): void {
    // Chart.js throws if a canvas is already bound to a live chart, so the previous
    // instance must be destroyed before the data change can be re-rendered.
    this.charts.get(key)?.destroy();
    this.charts.set(key, new Chart(canvas, config));
  }

  private drawSpecTimeline(): void {
    const history = this.specHistory();
    const canvas = this.specCanvas()?.nativeElement;
    if (!canvas || history.length === 0) return;

    this.draw('spec', canvas, {
      type: 'line',
      data: {
        labels: history.map((v) => this.shortCommit(v.commit)),
        datasets: [
          {
            label: 'Endpoints',
            data: history.map((v) => v.endpointCount),
            borderColor: BLUE,
            backgroundColor: BLUE,
            tension: 0.25,
            // The baseline version gets a visibly larger marker -- the one point on this
            // chart that everything else in the platform is diffed against.
            pointRadius: history.map((v) => (v.baseline ? 7 : 3)),
          },
          {
            label: 'Schemas',
            data: history.map((v) => v.schemaCount),
            borderColor: VIOLET,
            backgroundColor: VIOLET,
            tension: 0.25,
            pointRadius: history.map((v) => (v.baseline ? 7 : 3)),
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        scales: { y: { beginAtZero: true, ticks: { precision: 0 } } },
        plugins: {
          legend: { position: 'bottom' },
          tooltip: {
            callbacks: {
              afterTitle: (items) =>
                history[items[0].dataIndex]?.baseline ? 'published baseline' : '',
            },
          },
        },
      },
    });
  }

  private drawConsumers(): void {
    const apps = this.apps();
    const canvas = this.consumerCanvas()?.nativeElement;
    if (!canvas || apps.length === 0) return;

    this.draw('consumers', canvas, {
      type: 'bar',
      data: {
        labels: apps.map((a) => a.appName),
        datasets: [
          {
            label: 'Calls/day (seeded)',
            data: apps.map((a) => a.totalCallsPerDay),
            backgroundColor: BLUE,
            yAxisID: 'y',
          },
          {
            label: 'Compile-time use sites',
            data: apps.map((a) => a.usageRows),
            backgroundColor: TEAL,
            yAxisID: 'y1',
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        // Two axes on purpose: call volume is in thousands and use-site count is in tens,
        // so a shared axis would flatten the second series into the baseline.
        scales: {
          y: { type: 'linear', position: 'left', beginAtZero: true,
               title: { display: true, text: 'calls/day' } },
          y1: { type: 'linear', position: 'right', beginAtZero: true,
                grid: { drawOnChartArea: false },
                ticks: { precision: 0 },
                title: { display: true, text: 'use sites' } },
        },
        plugins: { legend: { position: 'bottom' } },
      },
    });
  }

  private drawTraffic(): void {
    const traffic = this.traffic();
    const canvas = this.trafficCanvas()?.nativeElement;
    if (!canvas || traffic.length === 0) return;

    this.draw('traffic', canvas, {
      type: 'bar',
      data: {
        labels: traffic.map((t) => t.location),
        datasets: [{
          label: 'Calls/day (seeded)',
          data: traffic.map((t) => t.callsPerDay),
          backgroundColor: INK,
        }],
      },
      options: {
        indexAxis: 'y',            // horizontal: endpoint labels are long
        responsive: true,
        maintainAspectRatio: false,
        scales: { x: { beginAtZero: true } },
        plugins: { legend: { display: false } },
      },
    });
  }
}