package main

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"clojure-sdk/internal/dagger"
	"clojure-sdk/internal/dagger/core"
	"github.com/Khan/genqlient/graphql"
	"github.com/vektah/gqlparser/v2/ast"
	"github.com/vektah/gqlparser/v2/parser"
)

// Exercise the actual runtime graph without starting an engine or containers.
type runtimeClient struct {
	existing bool
	requests []string
}

func (m *runtimeClient) MakeRequest(_ context.Context, req *graphql.Request, res *graphql.Response) error {
	m.requests = append(m.requests, req.Query)
	doc, err := parser.ParseQuery(&ast.Source{Input: req.Query})
	if err != nil {
		return err
	}
	var response func(ast.SelectionSet) map[string]any
	response = func(fields ast.SelectionSet) map[string]any {
		out := map[string]any{}
		for _, selection := range fields {
			if fragment, ok := selection.(*ast.InlineFragment); ok {
				for key, value := range response(fragment.SelectionSet) {
					out[key] = value
				}
				continue
			}
			field := selection.(*ast.Field)
			var value any
			if len(field.SelectionSet) > 0 {
				value = response(field.SelectionSet)
			} else {
				switch field.Name {
				case "sourceSubpath":
					value = ".dagger/ci"
				case "moduleOriginalName":
					value = "project-ci"
				case "exists":
					value = m.existing
				default:
					value = "mock-id"
				}
			}
			out[field.Alias] = value
		}
		return out
	}
	data, err := json.Marshal(response(doc.Operations[0].SelectionSet))
	if err != nil {
		return err
	}
	return json.Unmarshal(data, &res.Data)
}

func TestRuntimeInstallsSDKBeforeResolvingClasspath(t *testing.T) {
	for _, tc := range []struct {
		name             string
		existing, schema bool
	}{
		{"clean checkout without schema", false, false},
		{"generated bindings without schema", true, false},
		{"fresh schema", false, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			mock := &runtimeClient{existing: tc.existing}
			original := dag
			dag = &dagger.Client{Query: core.NewQuery(mock)}
			defer func() { dag = original }()
			sdk := &ClojureSdk{Source: dag.Directory()}
			module := dag.ModuleSource(".")
			var schema *dagger.File
			if tc.schema {
				schema = dag.Directory().File("schema.json")
			}
			ctr, err := sdk.ModuleRuntime(context.Background(), module, schema)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := ctr.ID(context.Background()); err != nil {
				t.Fatal(err)
			}
			queries := strings.Join(mock.requests, "\n")
			if !strings.Contains(queries, `"/src/.dagger/ci/dagger-sdk"`) {
				t.Fatal("runtime did not install the local SDK before clojure -P")
			}
			if !strings.Contains(queries, `"dagger.scaffold"`) {
				t.Fatal("runtime did not prepare module configuration")
			}
			generated := strings.Contains(queries, `"-M:generate"`)
			if generated != tc.schema {
				t.Fatalf("schema generation = %v, want %v", generated, tc.schema)
			}
			preserved := strings.Contains(queries, `directory(path:".dagger/ci/dagger-sdk")`)
			if preserved != tc.existing {
				t.Fatalf("preserved existing bindings = %v, want %v", preserved, tc.existing)
			}
		})
	}
}
