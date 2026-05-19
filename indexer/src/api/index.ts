import { db } from "ponder:api";
import schema from "ponder:schema";
import { graphql } from "ponder";
import { Hono } from "hono";

const app = new Hono();

app.use("/graphql", graphql({ db, schema }));

app.get("/hello", (c) => {
  return c.text("Hello, world!");
});

export default app;