import { Component, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';

/**
 * Local mirror of the platform's AgentDraft / AgentController payloads.
 * Hand-written rather than generated: contract-platform's own API is not part of the contract
 * this platform governs, and generating a client for it from its own spec would be a
 * circularity that confuses more than it saves.
 */
interface FieldProposal {
  targetSchema: string;
  fieldName: string;
  javaType: string;
  description: string;
  rationale: string;
  confidence: string;
  clarifyingQuestion: string;
}

interface FileEdit { path: string; unifiedDiff: string; }

interface ConsumerImpact {
  appName: string; team: string; screens: string[]; useSites: string[]; callsPerDay: number;
}

interface ImpactAssessment {
  location: string; classifiedSeverity: string; effectiveSeverity: string;
  verdict: string; consumers: ConsumerImpact[]; totalCallsPerDay: number;
}

interface ContractImpactReport {
  changed: boolean;
  highestSeverity: string;
  changes: { severity: string; category: string; location: string; description: string; }[];
  markdown: string;
  effectiveSeverity: string;
  impact: ImpactAssessment[];
}

interface AgentDraft {
  requestId: number; status: string; requestText: string;
  proposal: FieldProposal | null; fileEdits: FileEdit[];
  specDiffMarkdown: string | null; impact: ContractImpactReport | null;
  guardrails: string[]; blockedReason: string | null; prUrl: string | null;
}

interface AgentStatus {
  enabled: boolean; repository: string; baseBranch: string;
  canOpenPullRequests: boolean; allowedTypes: string[];
}

const PLATFORM = 'http://localhost:8081';

@Component({
  selector: 'app-contract-agent',
  imports: [FormsModule],
  templateUrl: './contract-agent.html',
  styleUrl: './contract-agent.css',
})
export class ContractAgent {
  private http = inject(HttpClient);

  // Signals, not plain fields: Angular 21 is zoneless by default, so a write inside
  // .subscribe() is only guaranteed to repaint when it goes through a signal.
  // Same lesson as OrderDetail on Day 04.
  requestText = signal("I need the customer's email on the order response");
  requester = signal('jay@synctank');
  draft = signal<AgentDraft | null>(null);
  status = signal<AgentStatus | null>(null);
  busy = signal(false);
  error = signal<string | null>(null);

  canApprove = computed(() => {
    const d = this.draft();
    return !!d && d.status === 'DRAFT' && !!this.status()?.canOpenPullRequests;
  });

  constructor() {
    this.http.get<AgentStatus>(`${PLATFORM}/agent/status`).subscribe({
      next: (s) => this.status.set(s),
      error: () => this.error.set(
        'Cannot reach contract-platform on 8081. Is it running, and is DashboardCorsConfig in place?'),
    });
  }

  submit(): void {
    this.busy.set(true);
    this.error.set(null);
    this.draft.set(null);

    this.http.post<AgentDraft>(`${PLATFORM}/agent/requests`, {
      request: this.requestText(),
      requester: this.requester(),
    }).subscribe({
      next: (d) => { this.draft.set(d); this.busy.set(false); },
      error: (e) => {
        this.error.set(e?.error?.message ?? 'The agent could not draft this change.');
        this.busy.set(false);
      },
    });
  }

  approve(): void {
    const current = this.draft();
    if (!current) return;
    this.busy.set(true);

    this.http.post<AgentDraft>(`${PLATFORM}/agent/requests/${current.requestId}/approve`, {
      actor: this.requester(),
    }).subscribe({
      next: (d) => { this.draft.set(d); this.busy.set(false); },
      error: (e) => {
        this.error.set(e?.error?.message ?? 'Opening the pull request failed.');
        this.busy.set(false);
      },
    });
  }

  reject(): void {
    const current = this.draft();
    if (!current) return;
    this.http.post<AgentDraft>(`${PLATFORM}/agent/requests/${current.requestId}/reject`, {
      actor: this.requester(),
      reason: 'Rejected from the dashboard',
    }).subscribe({ next: (d) => this.draft.set(d) });
  }
}