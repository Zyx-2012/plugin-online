import axios from "axios";
import type { OnlineStatItem, OnlineSummary } from "./types";

const http = axios.create({
  baseURL: "/",
  timeout: 3000,
});

export async function fetchSummary() {
  const { data } = await http.get<OnlineSummary>(
    "/apis/online-user.zyx2012.cn/v1alpha1/stats/summary"
  );
  return data;
}

export async function fetchStats() {
  const { data } = await http.get<OnlineStatItem[]>(
    "/apis/online-user.zyx2012.cn/v1alpha1/stats"
  );
  return data;
}