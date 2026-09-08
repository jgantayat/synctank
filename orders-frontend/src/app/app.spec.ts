import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      // App renders <app-order-detail>, <app-dashboard> and <app-contract-agent>, all of
      // which inject HttpClient and all of which fire requests on construction.
      //
      // Day 08 adds provideHttpClientTesting alongside the real provider: without it the
      // root test depends on jsdom failing five live requests to localhost quietly enough
      // that no error escapes. With it, the requests are captured and never answered --
      // deterministic, and no network is touched. HttpTestingController.verify() is
      // deliberately NOT called here; unanswered requests are the expected state.
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });
});