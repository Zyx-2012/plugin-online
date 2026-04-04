export interface OnlineStatItem {
    uri: string;
    count: number;
    lastActiveAt: string | null;
    viewUrl: string;
  }
  
  export interface OnlineSummary {
    total: number;
    peak24h: number;
    activePages: number;
    updatedAt: string;
    wsActive: boolean;
  }