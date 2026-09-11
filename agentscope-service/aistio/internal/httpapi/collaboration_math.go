// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"math/big"
	"regexp"
	"strings"
)

var decimalLiteral = regexp.MustCompile(`^(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)$`)

// Evaluate bounded arithmetic without running code, accessing files or losing
// integer precision. Non-terminating divisions are returned as exact fractions.
func evaluateArithmetic(expression string) (map[string]any, error) {
	if len(expression) == 0 || len(expression) > 512 {
		return nil, fmt.Errorf("expression must contain 1–512 characters")
	}
	normalized := strings.NewReplacer("×", "*", "÷", "/", "−", "-").Replace(expression)
	tree, err := parser.ParseExpr(normalized)
	if err != nil {
		return nil, fmt.Errorf("invalid arithmetic expression: %w", err)
	}
	var eval func(ast.Expr, int) (*big.Rat, error)
	eval = func(node ast.Expr, depth int) (*big.Rat, error) {
		if depth > 64 {
			return nil, fmt.Errorf("expression is too deeply nested")
		}
		switch n := node.(type) {
		case *ast.ParenExpr:
			return eval(n.X, depth+1)
		case *ast.BasicLit:
			if !decimalLiteral.MatchString(n.Value) {
				return nil, fmt.Errorf("only decimal numbers are supported")
			}
			v, ok := new(big.Rat).SetString(n.Value)
			if !ok {
				return nil, fmt.Errorf("invalid number")
			}
			return v, nil
		case *ast.UnaryExpr:
			v, err := eval(n.X, depth+1)
			if err != nil {
				return nil, err
			}
			if n.Op == token.SUB {
				return v.Neg(v), nil
			}
			if n.Op == token.ADD {
				return v, nil
			}
		case *ast.BinaryExpr:
			a, err := eval(n.X, depth+1)
			if err != nil {
				return nil, err
			}
			b, err := eval(n.Y, depth+1)
			if err != nil {
				return nil, err
			}
			v := new(big.Rat)
			switch n.Op {
			case token.ADD:
				v.Add(a, b)
			case token.SUB:
				v.Sub(a, b)
			case token.MUL:
				v.Mul(a, b)
			case token.QUO:
				if b.Sign() == 0 {
					return nil, fmt.Errorf("division by zero")
				}
				v.Quo(a, b)
			case token.REM:
				if !a.IsInt() || !b.IsInt() || b.Sign() == 0 {
					return nil, fmt.Errorf("remainder requires integers and a nonzero divisor")
				}
				v.SetInt(new(big.Int).Rem(a.Num(), b.Num()))
			default:
				return nil, fmt.Errorf("only +, -, *, /, %% and parentheses are supported")
			}
			if v.Num().BitLen() > 8192 || v.Denom().BitLen() > 8192 {
				return nil, fmt.Errorf("result exceeds arithmetic limit")
			}
			return v, nil
		}
		return nil, fmt.Errorf("only numeric arithmetic is supported")
	}
	value, err := eval(tree, 0)
	if err != nil {
		return nil, err
	}
	return map[string]any{"expression": expression, "result": value.RatString(), "exact": true}, nil
}
